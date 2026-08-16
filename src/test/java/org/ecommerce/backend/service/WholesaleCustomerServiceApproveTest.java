package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.entity.WholesaleProfileEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed tests for {@link WholesaleCustomerService#approveWholesaleApplication}'s
 * account-creation and account-upgrade branches. Real {@link EntityManager}, no mocks —
 * this exercises real JPA relationships (UserEntity/CustomerEntity/WholesaleProfileEntity),
 * which a PanacheMock-based test cannot verify. {@link TestTransaction} rolls back after
 * each test so the shared dev DB is never mutated, mirroring {@link WholesaleDecisionGuardTest}.
 */
@QuarkusTest
class WholesaleCustomerServiceApproveTest
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @Inject
    EntityManager em;

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String uniqueEmail(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private WholesaleApplicationEntity createPendingApplication(String accountEmail)
    {
        WholesaleApplicationEntity app = new WholesaleApplicationEntity();
        app.setApplicantEmail(accountEmail);
        app.setAccountEmail(accountEmail);
        app.setFirstName("Applicant");
        app.setLastName("Person");
        app.setCompanyName("Application Co");
        app.setVatNumber("VAT-APP-1");
        app.setRegNumber("REG-APP-1");
        app.setStatus(WholesaleApplicationStatusEn.PENDING);
        app.setPhysicalAddressLine1("1 Application Rd");
        app.setPhysicalCity("Cape Town");
        app.setPhysicalProvince("Western Cape");
        app.setPhysicalPostalCode("8001");
        app.setPostalAddressLine1("PO Box 1");
        app.setPostalCity("Cape Town");
        app.setPostalProvince("Western Cape");
        app.setPostalPostalCode("8000");
        em.persist(app);
        em.flush();
        return app;
    }

    private UserEntity createUser(String email)
    {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash("existing-real-hash");
        em.persist(user);
        em.flush();
        return user;
    }

    private CustomerEntity createCustomer(UserEntity user, CustomerTypeEn shopperType, CustomerStatusEn status)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setShopperType(shopperType);
        customer.setStatus(status);
        customer.setFirstName("Existing");
        customer.setLastName("Customer");
        em.persist(customer);
        em.flush();
        return customer;
    }

    private WholesaleApplicationEntity approveAndReload(UUID applicationId)
    {
        wholesaleCustomerService.approveWholesaleApplication(applicationId);
        em.flush();
        em.clear();
        return em.find(WholesaleApplicationEntity.class, applicationId);
    }

    // ── Case 1: brand-new email — baseline/regression for the extracted helper ──

    @Test
    @TestTransaction
    void approve_newEmail_createsAccount()
    {
        String email = uniqueEmail("brandnew");
        WholesaleApplicationEntity app = createPendingApplication(email);

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.getStatus());
        CustomerEntity customer = persisted.getCustomer();
        assertNotNull(customer);
        assertEquals(CustomerTypeEn.WHOLESALER, customer.getShopperType());
        assertEquals(CustomerStatusEn.ACTIVE, customer.getStatus());
        assertEquals("", customer.getUser().getPasswordHash());
        assertNotNull(customer.getWholesaleProfile());
        assertEquals("Application Co", customer.getWholesaleProfile().getCompanyName());
    }

    // ── Case 2: existing RETAILER/ACTIVE account — upgraded in place ────────────

    @Test
    @TestTransaction
    void approve_existingRetailerActiveAccount_upgradesInPlace()
    {
        String email = uniqueEmail("existing-retailer");
        UserEntity user = createUser(email);
        CustomerEntity existingCustomer = createCustomer(user, CustomerTypeEn.RETAILER, CustomerStatusEn.ACTIVE);
        WholesaleApplicationEntity app = createPendingApplication(email);
        UUID existingCustomerId = existingCustomer.getId();
        UUID existingUserId = user.getId();
        em.clear(); // force a fresh DB-backed load, matching a real (separate) request's persistence context

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.getStatus());
        CustomerEntity customer = persisted.getCustomer();
        assertNotNull(customer);
        assertEquals(existingCustomerId, customer.getId(), "must upgrade the existing customer, not create a new one");
        assertEquals(existingUserId, customer.getUser().getId(), "no new UserEntity should be created");
        assertEquals(CustomerTypeEn.WHOLESALER, customer.getShopperType());
        assertEquals(CustomerStatusEn.ACTIVE, customer.getStatus());
        assertEquals("existing-real-hash", customer.getUser().getPasswordHash(), "existing password must be left untouched");
        assertNotNull(customer.getWholesaleProfile(), "a wholesale profile must be created for the upgraded customer");
        assertEquals("Application Co", customer.getWholesaleProfile().getCompanyName());
    }

    // ── Case 3: existing PENDING account — promoted to ACTIVE ───────────────────

    @Test
    @TestTransaction
    void approve_existingPendingAccount_promotesToActive()
    {
        String email = uniqueEmail("existing-pending");
        UserEntity user = createUser(email);
        createCustomer(user, CustomerTypeEn.RETAILER, CustomerStatusEn.PENDING);
        WholesaleApplicationEntity app = createPendingApplication(email);
        em.clear();

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        assertEquals(CustomerStatusEn.ACTIVE, persisted.getCustomer().getStatus());
        assertEquals(CustomerTypeEn.WHOLESALER, persisted.getCustomer().getShopperType());
    }

    // ── Case 4: existing DISABLED account — status is NOT silently reactivated ──

    @Test
    @TestTransaction
    void approve_existingDisabledAccount_leavesStatusDisabled()
    {
        String email = uniqueEmail("existing-disabled");
        UserEntity user = createUser(email);
        createCustomer(user, CustomerTypeEn.RETAILER, CustomerStatusEn.DISABLED);
        WholesaleApplicationEntity app = createPendingApplication(email);
        em.clear();

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        assertEquals(CustomerStatusEn.DISABLED, persisted.getCustomer().getStatus(),
                "approval must not silently reactivate a staff-disabled account");
        assertEquals(CustomerTypeEn.WHOLESALER, persisted.getCustomer().getShopperType(),
                "shopperType still upgrades even though status is left alone");
    }

    // ── Case 5: existing WHOLESALER account — true idempotent no-op ─────────────

    @Test
    @TestTransaction
    void approve_existingWholesalerAccount_isIdempotentNoOp()
    {
        String email = uniqueEmail("existing-wholesaler");
        UserEntity user = createUser(email);
        CustomerEntity customer = createCustomer(user, CustomerTypeEn.WHOLESALER, CustomerStatusEn.ACTIVE);

        WholesaleProfileEntity profile = new WholesaleProfileEntity();
        profile.setCustomer(customer);
        profile.setCompanyName("Divergent Existing Co");
        profile.setVatNumber("VAT-DIVERGENT");
        em.persist(profile);
        em.flush();

        WholesaleApplicationEntity app = createPendingApplication(email);
        em.clear();

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        assertEquals(WholesaleApplicationStatusEn.APPROVED, persisted.getStatus());
        assertEquals(customer.getId(), persisted.getCustomer().getId());
        assertEquals("Divergent Existing Co", persisted.getCustomer().getWholesaleProfile().getCompanyName(),
                "an already-wholesale customer's profile must not be silently overwritten by a later approval");
        assertEquals("VAT-DIVERGENT", persisted.getCustomer().getWholesaleProfile().getVatNumber());
    }

    // ── Case 6: defensive — a UserEntity with no linked CustomerEntity ──────────

    @Test
    @TestTransaction
    void approve_userWithNoLinkedCustomer_throws()
    {
        String email = uniqueEmail("orphan-user");
        createUser(email); // no CustomerEntity created for this user
        WholesaleApplicationEntity app = createPendingApplication(email);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.approveWholesaleApplication(app.getId()));

        assertTrue(ex.getMessage().contains(email), "error should identify the affected account");

        em.clear();
        WholesaleApplicationEntity persisted = em.find(WholesaleApplicationEntity.class, app.getId());
        assertEquals(WholesaleApplicationStatusEn.PENDING, persisted.getStatus(), "no partial state change on failure");
    }

    // ── Case 7: upgrade path applies addresses from the application ─────────────

    @Test
    @TestTransaction
    void approve_existingAccountWithNoAddresses_appliesAddressesFromApplication()
    {
        String email = uniqueEmail("existing-no-address");
        UserEntity user = createUser(email);
        createCustomer(user, CustomerTypeEn.RETAILER, CustomerStatusEn.ACTIVE);
        WholesaleApplicationEntity app = createPendingApplication(email);
        em.clear();

        WholesaleApplicationEntity persisted = approveAndReload(app.getId());

        CustomerEntity customer = persisted.getCustomer();
        assertNotNull(customer.getPhysicalAddress(), "physical address should be created from the application");
        assertEquals("1 Application Rd", customer.getPhysicalAddress().getAddressLine1());
        assertNotNull(customer.getPostalAddress(), "postal address should be created from the application");
        assertEquals("PO Box 1", customer.getPostalAddress().getAddressLine1());
    }
}
