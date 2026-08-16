package org.ecommerce.backend.service;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Duration;
import java.util.UUID;

/**
 * Mints and verifies the order capability token (design.md §3) — a short-lived token
 * bound to exactly one order id, carrying no {@code groups} claim so it grants no role
 * or identity to Quarkus's own JWT auth mechanism if ever presented as a login
 * credential (§3.1). Signed with the same key and issuer {@link CustomerAuthService}
 * and {@link AdminAuthService} already use; verified directly against
 * {@link JWTParser} rather than through {@code SecurityIdentity}, so its presence
 * cannot affect any existing {@code hasRole} check in the codebase (§3.2).
 */
@ApplicationScoped
public class OrderCapabilityService
{
    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE_VALUE = "order-capability";

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @ConfigProperty(name = "order.capability.ttl-minutes")
    long ttlMinutes;

    @Inject
    JWTParser jwtParser;

    public String mint(UUID orderId)
    {
        return Jwt.issuer(issuer)
                .subject(orderId.toString())
                .claim(SCOPE_CLAIM, SCOPE_VALUE)
                .expiresIn(Duration.ofMinutes(ttlMinutes))
                .sign();
    }

    /**
     * {@code JWTParser.parse} verifies the signature and rejects an expired token in
     * the same call, so a bad signature, a malformed string and an expired token all
     * fail identically here — there is no separate expiry branch to get wrong.
     */
    public boolean isValidFor(String token, UUID orderId)
    {
        if (token == null || token.isBlank() || orderId == null) {
            return false;
        }

        try {
            JsonWebToken parsed = jwtParser.parse(token);
            return SCOPE_VALUE.equals(parsed.getClaim(SCOPE_CLAIM))
                    && orderId.toString().equals(parsed.getSubject());
        } catch (ParseException e) {
            return false;
        }
    }
}
