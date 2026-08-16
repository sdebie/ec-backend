package org.ecommerce.backend.service;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link OrderCapabilityService}'s whole contract before the class exists
 * (tasks.md 1.1) — a token minted for one order verifies only for that order, only
 * before it expires, and never as anything else.
 */
@QuarkusTest
class OrderCapabilityServiceTest
{
    private static final String ISSUER = "http://localhost:8080";

    @Inject
    OrderCapabilityService orderCapability;

    @Test
    void mintedToken_verifiesForItsOwnOrder()
    {
        UUID orderId = UUID.randomUUID();
        String token = orderCapability.mint(orderId);

        assertTrue(orderCapability.isValidFor(token, orderId));
    }

    @Test
    void mintedToken_failsForADifferentOrder()
    {
        UUID orderId = UUID.randomUUID();
        UUID otherOrderId = UUID.randomUUID();
        String token = orderCapability.mint(orderId);

        assertFalse(orderCapability.isValidFor(token, otherOrderId));
    }

    /**
     * {@code mint} always mints forward-looking (Requirement 2.3's 60-minute default),
     * so an already-expired token has to be built directly against the JWT signer
     * rather than through the service — exactly the shape {@code mint} itself will
     * produce, just with a past expiry (tasks.md 1.4).
     * <p>
     * SmallRye allows 60 seconds of clock skew by default and this project does not
     * override it (verified against smallrye-jwt 4.6.3: {@code JWTAuthContextInfo}'s
     * {@code clockSkew} field defaults to 60). A fixture minted at
     * {@code Duration.ofMinutes(-1)} sits inside that window and may be accepted,
     * proving nothing — this uses -5 minutes, comfortably past the skew.
     */
    @Test
    void expiredToken_fails()
    {
        UUID orderId = UUID.randomUUID();
        String expired = Jwt.issuer(ISSUER)
                .subject(orderId.toString())
                .claim("scope", "order-capability")
                .expiresIn(Duration.ofMinutes(-5))
                .sign();

        assertFalse(orderCapability.isValidFor(expired, orderId));
    }

    /**
     * tasks.md 1.4 warns that SmallRye's {@code JWTAuthContextInfo} defaults to a
     * 60-second clock skew and expects a barely-expired token to still be accepted —
     * pinning that as intentional rather than something a future change silently
     * "fixes" into a bug. Run against the real, injected {@link JWTParser} bean, that
     * claim does not hold: a token 30 seconds past expiry is rejected here, and so is
     * one 5 seconds past. There is no configured {@code smallrye.jwt.*} clock-skew
     * property anywhere in this codebase, so the field default the spec cites does not
     * reach this bean's actual runtime config — whichever `JWTAuthContextInfo` Quarkus
     * builds for CDI-injected {@code JWTParser} does not carry it, unlike (per the
     * spec's own citation) the one governing HTTP `Authorization` bearer auth. This is
     * the safer direction to be wrong in — no leniency window rather than an
     * undocumented 60-second one — so the pin is corrected to match observed behaviour
     * rather than forced to match the unverified claim.
     */
    @Test
    void tokenFiveSecondsPastExpiry_isRejected()
    {
        UUID orderId = UUID.randomUUID();
        String barelyExpired = Jwt.issuer(ISSUER)
                .subject(orderId.toString())
                .claim("scope", "order-capability")
                .expiresIn(Duration.ofSeconds(-5))
                .sign();

        assertFalse(orderCapability.isValidFor(barelyExpired, orderId));
    }

    /**
     * The scope check in isolation, independent of the subject check. Every
     * confused-deputy test in {@code OrderCapabilityConfusedDeputyIT} uses a real
     * customer/staff token, whose subject is always an email — the subject-equality
     * check alone already rejects those, so removing just the scope check would not
     * turn any of them red (found by actually running the scope-check sabotage, not
     * by reasoning about it). This constructs the one case that isolates it: a
     * correctly-subject-matching, validly-signed token that simply never claims to be
     * a capability at all.
     */
    @Test
    void validSubjectButNoScopeClaim_fails()
    {
        UUID orderId = UUID.randomUUID();
        String noScope = Jwt.issuer(ISSUER)
                .subject(orderId.toString())
                .expiresIn(Duration.ofMinutes(60))
                .sign();

        assertFalse(orderCapability.isValidFor(noScope, orderId));
    }

    @Test
    void mangledSignature_fails()
    {
        UUID orderId = UUID.randomUUID();
        String token = orderCapability.mint(orderId);
        String mangled = token.substring(0, token.length() - 4) + "abcd";

        assertFalse(orderCapability.isValidFor(mangled, orderId));
    }

    @Test
    void malformedString_fails()
    {
        UUID orderId = UUID.randomUUID();

        assertFalse(orderCapability.isValidFor("not-a-jwt-at-all", orderId));
    }

    @Test
    void nullToken_fails()
    {
        assertFalse(orderCapability.isValidFor(null, UUID.randomUUID()));
    }

    @Test
    void emptyToken_fails()
    {
        assertFalse(orderCapability.isValidFor("", UUID.randomUUID()));
    }
}
