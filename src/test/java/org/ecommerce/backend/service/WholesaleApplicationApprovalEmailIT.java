package org.ecommerce.backend.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the full commit-to-inbox path: {@link WholesaleCustomerService#approveWholesaleApplication}
 * computes {@code newAccountCreated} correctly AND it reaches a genuinely-sent email with the right
 * copy. Unit tests alone cannot prove this — the {@code AFTER_SUCCESS} CDI observer that sends the
 * mail only fires on a real commit, and {@link WholesaleMailNotifierTest}/{@link WholesaleStatusTemplateRenderTest}
 * both hand-build their inputs, bypassing {@link WholesaleCustomerService} entirely.
 * <p>
 * Cannot use {@link io.quarkus.test.TestTransaction} — that rolls back at test end, so the observer
 * never fires (see {@link WholesaleMailIntegrationTest}, which relies on exactly that rollback for a
 * different property). Follows this codebase's tracked-ID + manual {@code @AfterEach} cleanup
 * convention instead (the same one {@code OrderServiceCreateOrderFromCartConcurrencyIT} uses).
 */
@QuarkusTest
@DisplayName("WholesaleApplicationApprovalEmailIT — real commit reaches a real, correctly-branched email")
class WholesaleApplicationApprovalEmailIT
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    @Inject
    MockMailbox mailbox;

    // Real (non-mock) injection: the UserEntity.findByEmail() call in cleanup() below is
    // direct real-DB test teardown code (never a when()/verify() stub) for a genuine
    // @QuarkusTest IT that exercises WholesaleCustomerService against real committed rows
    // (see the class javadoc). @InjectMock would replace this bean application-wide,
    // including inside the production code approveWholesaleApplication() exercises, silently
    // turning this into a check against an unstubbed mock. @Inject preserves the exact
    // real-DB behaviour the removed UserEntity.findByEmail() static had.
    @Inject
    UserRepository userRepository;

    private final List<UUID> createdApplicationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> createdEmails = Collections.synchronizedList(new ArrayList<>());

    private String uniqueEmail(String prefix)
    {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        createdEmails.add(email);
        return email;
    }

    private UUID persistPendingApplication(String email)
    {
        UUID appId = QuarkusTransaction.requiringNew().call(() -> {
            WholesaleApplicationEntity app = new WholesaleApplicationEntity();
            app.setApplicantEmail(email);
            app.setAccountEmail(email);
            app.setFirstName("Test");
            app.setLastName("Applicant");
            app.setCompanyName("IT Test Co");
            app.setStatus(WholesaleApplicationStatusEn.PENDING);
            em.persist(app);
            return app.getId();
        });
        createdApplicationIds.add(appId);
        return appId;
    }

    private void persistExistingRetailAccount(String email)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            UserEntity user = new UserEntity();
            user.setEmail(email);
            user.setPasswordHash("real-existing-hash");
            em.persist(user);

            CustomerEntity customer = new CustomerEntity();
            customer.setUser(user);
            customer.setShopperType(CustomerTypeEn.RETAILER);
            customer.setStatus(CustomerStatusEn.ACTIVE);
            customer.setFirstName("Existing");
            customer.setLastName("Customer");
            em.persist(customer);
        });
    }

    @AfterEach
    @Transactional
    void cleanup()
    {
        for (UUID appId : createdApplicationIds) {
            em.createQuery("delete from WholesaleApplicationEntity a where a.id = :id").setParameter("id", appId).executeUpdate();
        }
        for (String email : createdEmails) {
            UserEntity user = userRepository.findByEmail(email);
            if (user != null) {
                CustomerEntity customer = user.getCustomer();
                if (customer != null) {
                    customer.getWholesaleProfile(); // force-init before remove, so cascade reaches it
                    customer.getAddresses().size();
                    em.remove(customer);
                }
                em.remove(user);
            }
        }
        mailbox.clear();
    }

    @Test
    @DisplayName("new account: email contains the forgot-password link, not \"Simply log in\"")
    void newAccountApproval_emailHasForgotPasswordLink()
    {
        String email = uniqueEmail("it-new");
        UUID appId = persistPendingApplication(email);

        wholesaleCustomerService.approveWholesaleApplication(appId);

        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertEquals(1, mails.size(), "exactly one decision email should be sent");
        String html = mails.get(0).getHtml();
        assertTrue(html.contains("/account/forgot-password"), "must link to the forgot-password flow");
        assertFalse(html.contains("Simply log in"), "a brand-new account has no working password yet");
    }

    @Test
    @DisplayName("upgraded existing account: email says \"Simply log in\", no forgot-password link")
    void upgradedAccountApproval_emailSaysSimplyLogIn()
    {
        String email = uniqueEmail("it-upgrade");
        persistExistingRetailAccount(email);
        UUID appId = persistPendingApplication(email);

        wholesaleCustomerService.approveWholesaleApplication(appId);

        List<Mail> mails = mailbox.getMailsSentTo(email);
        assertEquals(1, mails.size(), "exactly one decision email should be sent");
        String html = mails.get(0).getHtml();
        assertTrue(html.contains("Simply log in"), "the existing password still works");
        assertFalse(html.contains("/account/forgot-password"), "no need to send a link when the password already works");
    }
}
