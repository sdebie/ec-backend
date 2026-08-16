package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * design.md §3.3: one signing key mints both login tokens and capability tokens, so a
 * verifier that checks only the signature accepts either as the other. Both checks
 * (scope, subject) are load-bearing (tasks.md 1.2) — a signature-only implementation
 * ships green without these.
 * <p>
 * (a)/(b) mint a FRESH token at test time through the real minting services, never a
 * captured fixture string (Requirement 2.4a) — an expired customer/staff JWT is
 * refused by {@code JWTParser.parse} before the {@code scope} claim is ever read, so a
 * test built on a stale pasted token would pass without exercising the discriminator
 * at all, and keep passing after the scope check is deleted.
 */
@QuarkusTest
class OrderCapabilityConfusedDeputyIT
{
    @Inject
    OrderCapabilityService orderCapability;

    @Inject
    CustomerAuthService customerAuthService;

    @Test
    void customerLoginToken_presentedAsOrderToken_isRefused()
    {
        UUID orderId = UUID.randomUUID();

        UserEntity user = new UserEntity();
        user.setEmail("confused-deputy-" + UUID.randomUUID() + "@test.com");
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());
        customer.setUser(user);
        customer.setShopperType(CustomerTypeEn.RETAILER);

        // A real customer login token, freshly minted through the actual service —
        // not a captured fixture, which would be indistinguishable-when-expired from
        // one that never exercised the scope check at all.
        String customerToken = customerAuthService.generateToken(customer);

        assertFalse(orderCapability.isValidFor(customerToken, orderId),
                "a customer login token has no scope: order-capability claim and must never verify as one");
    }

    @Test
    void staffLoginToken_presentedAsOrderToken_isRefused()
    {
        UUID orderId = UUID.randomUUID();

        // AdminAuthService.generateToken is private, reachable only via authenticate()
        // against a real, seeded StaffUserEntity + bcrypt password — DB provisioning
        // this assertion has no need of. A hand-built token with the identical claim
        // shape (issuer + subject + groups + full_name, no scope), signed at test time
        // against the same configured key, is equivalent for what's actually under
        // test here: the scope discriminator, not staff provisioning.
        String staffToken = Jwt.issuer("http://localhost:8080")
                .subject("staff-" + UUID.randomUUID() + "@test.com")
                .groups("SUPER_ADMIN")
                .claim("full_name", "Test Staff")
                .expiresIn(java.time.Duration.ofHours(8))
                .sign();

        assertFalse(orderCapability.isValidFor(staffToken, orderId),
                "a staff login token has no scope: order-capability claim and must never verify as one");
    }

    /**
     * Proven through {@code myOrders} — a real GraphQL surface that reads
     * {@code SecurityIdentity}/{@code JsonWebToken} — rather than by reading the
     * token's claims back in the test. A capability token's {@code subject} is an
     * order id, not an email, and it carries no {@code groups} claim, so it must fail
     * exactly as an absent token does: "Unauthorized", never a customer's order
     * history.
     */
    @Test
    void capabilityToken_presentedAsAuthorizationBearer_grantsNoCustomerIdentity()
    {
        UUID orderId = UUID.randomUUID();
        String capabilityToken = orderCapability.mint(orderId);

        given()
                .header("Authorization", "Bearer " + capabilityToken)
                .contentType("application/json")
                .body("{\"query\": \"{ myOrders { id } }\"}")
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Unauthorized"))
                .body("data.myOrders", org.hamcrest.Matchers.nullValue());
    }
}
