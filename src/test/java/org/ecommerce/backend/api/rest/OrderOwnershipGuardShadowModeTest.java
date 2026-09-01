package org.ecommerce.backend.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * tasks.md task 2.5: a repo-wide grep for {@code shadow} matches only
 * {@link OrderOwnershipGuard} itself — the {@code legacyMayAccess} fallback under
 * {@code order.capability.enforce=false} has no other test exercising it.
 * <p>
 * {@link OrderOwnershipGuardTest} cannot reach that branch: it runs under the
 * project-wide default (no {@code %test} override for {@code order.capability.enforce},
 * by design — see {@code application.properties}), so {@code mayAct}'s shadow fallback
 * is dead code as far as that class is concerned. This class forces the flag off via
 * {@link TestProfile} to reach it.
 * <p>
 * The truth table's "flag on" half is not repeated here — it would force a second,
 * redundant application restart to re-prove what the default-profile suite already
 * pins. {@link OrderOwnershipGuardTest#guestOrder_noCredential_refused()} is the
 * structurally identical case (guest order, no credential) under the default
 * enforce-on profile: {@code mayAct} refuses, and — unlike here — never reaches
 * {@code legacyMayAccess} to ask it a second opinion.
 * <p>
 * The log assertion below reads a real Quarkus file-log handler rather than attaching
 * a {@code java.util.logging.Handler} or swapping {@code System.out} from the test:
 * both were tried and both saw nothing, even though the WARN line printed to the
 * console right in front of them. Quarkus wires its console handler to a target
 * fixed at boot, before any {@code @BeforeEach}/{@code @Test} code runs against the
 * already-started application — so neither a handler attached after boot nor a
 * {@code System.out} swap after boot can intercept it. A file handler enabled through
 * this same {@link TestProfile}'s config overrides, by contrast, is wired from that
 * config *during* this class's own boot, so it is live before the first test runs;
 * reading the file back afterward needs no interception at all.
 */
@QuarkusTest
@TestProfile(OrderOwnershipGuardShadowModeTest.EnforceOffProfile.class)
class OrderOwnershipGuardShadowModeTest
{
    private static final String LOG_FILE = "target/order-ownership-guard-shadow-mode-test.log";

    public static class EnforceOffProfile implements QuarkusTestProfile
    {
        @Override
        public Map<String, String> getConfigOverrides()
        {
            return Map.of(
                    "order.capability.enforce", "false",
                    "quarkus.log.file.enable", "true",
                    "quarkus.log.file.path", LOG_FILE
            );
        }
    }

    @Inject
    OrderOwnershipGuard guard;

    @InjectMock
    SecurityIdentity securityIdentity;

    @InjectMock
    JsonWebToken jwt;

    @InjectMock
    CustomerRepository customerRepository;

    private UUID orderId;
    private OrderEntity guestOrder;
    private OrderEntity customerOrder;

    @BeforeEach
    void setUp()
    {
        orderId = UUID.randomUUID();

        guestOrder = new OrderEntity();
        guestOrder.setId(orderId);
        guestOrder.setCustomerEntity(null);

        CustomerEntity owner = new CustomerEntity();
        owner.setId(UUID.randomUUID());
        customerOrder = new OrderEntity();
        customerOrder.setId(orderId);
        customerOrder.setCustomerEntity(owner);

        when(securityIdentity.hasRole("customer")).thenReturn(false);
    }

    private void authenticateAsDifferentCustomer()
    {
        String email = "stranger@test.com";
        when(securityIdentity.hasRole("customer")).thenReturn(true);
        when(jwt.getSubject()).thenReturn(email);
        CustomerEntity stranger = new CustomerEntity();
        stranger.setId(UUID.randomUUID());
        when(customerRepository.findByEmail(email)).thenReturn(stranger);
    }

    private String readLogFile() throws IOException
    {
        Path path = Path.of(LOG_FILE);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("flag off: mayAct's own rule refuses but legacyMayAccess admits -> succeeds and logs the shadow warning")
    void shadowMode_ruleRefusesLegacyAdmits_succeedsAndLogs() throws IOException
    {
        // Guest order, no credential: the real rule has nothing to authorize it (no
        // token, no owning customer to match). legacyMayAccess admits any non-customer
        // caller unconditionally for a guest order -- that permissiveness is exactly
        // today's pre-spec rule, deliberately preserved while enforcement is off.
        boolean result = guard.mayAct(guestOrder, null);

        assertTrue(result, "shadow mode must fall back to legacyMayAccess, not refuse outright");

        String logged = readLogFile();
        assertTrue(
                logged.contains("WARN") && logged.contains("order-capability shadow") && logged.contains(orderId.toString()),
                "the shadow fallback must WARN-log the order id it would have refused under enforcement; log file was:\n" + logged
        );
    }

    @Test
    @DisplayName("flag off: mayAct's own rule refuses and legacyMayAccess also refuses -> still fails")
    void shadowMode_ruleRefusesLegacyAlsoRefuses_stillFails()
    {
        // Customer-owned order, a different customer's JWT: legacyMayAccess IS today's
        // rule, and today's rule already refuses a stranger a customer's order -- the
        // shadow fallback is a fallback to that protection, not a removal of it.
        authenticateAsDifferentCustomer();

        assertFalse(guard.mayAct(customerOrder, null));
    }
}
