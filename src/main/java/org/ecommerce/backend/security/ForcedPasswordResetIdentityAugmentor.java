package org.ecommerce.backend.security;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.entity.StaffUserEntity;

import java.util.Set;

/**
 * Server-side mirror of the forced-password-reset gate. A staff JWT carries the
 * account's real role for its full 8-hour life regardless of {@code staff_users.
 * reset_password} — the flag was previously only ever reported in a response body
 * (AdminMeResource, StaffResource#login), never enforced, so a flagged account's
 * token still worked on every other {@code @RolesAllowed} endpoint.
 * <p>
 * This augmentor re-checks the flag against the database on every authenticated
 * staff request and, while it's set, replaces the identity's roles with a single
 * sentinel role. Every other {@code @RolesAllowed} check in the admin surface —
 * REST and GraphQL alike, since both rely on the same mechanism — then fails
 * automatically with no per-endpoint change needed. The two endpoints a flagged
 * account must still reach (self-service reset, {@code /admin/me}) opt back in by
 * naming {@link #PASSWORD_RESET_REQUIRED_ROLE} in their own {@code @RolesAllowed}.
 * <p>
 * Re-checked on every request rather than baked into the JWT at login: a flag set
 * mid-session (an admin forces a reset on an already-logged-in account) must take
 * effect immediately, not after the existing token's remaining lifetime.
 * <p>
 * A staff email with no matching row is passed through unchanged rather than
 * failing closed — deliberately out of scope here. Revoking a deleted/deactivated
 * account's still-valid token is the same unaddressed gap as {@code isActive} only
 * being checked at login (tracked as {@code docs/KNOWN-LIMITATIONS.md} item 11),
 * not something this flag's fix should silently also take on; closing it here
 * broke the established self-signed-JWT-without-a-DB-row convention used
 * throughout the staff/admin test suite for pure role-matrix tests.
 */
@ApplicationScoped
public class ForcedPasswordResetIdentityAugmentor implements SecurityIdentityAugmentor
{
    public static final String PASSWORD_RESET_REQUIRED_ROLE = "PASSWORD_RESET_REQUIRED";

    private static final Set<String> STAFF_ROLES = Set.of("SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER");

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context)
    {
        if (identity.isAnonymous() || identity.getRoles().stream().noneMatch(STAFF_ROLES::contains)) {
            return Uni.createFrom().item(identity);
        }
        return context.runBlocking(() -> augmentIfResetRequired(identity));
    }

    private SecurityIdentity augmentIfResetRequired(SecurityIdentity identity)
    {
        // context.runBlocking hands this a worker thread with no ambient transaction or
        // CDI request scope, so the Panache read needs its own explicit transaction —
        // the same requiringNew() pattern StockRecoveryJob uses for the same reason.
        boolean mustReset = QuarkusTransaction.requiringNew().call(() -> {
            StaffUserEntity user = StaffUserEntity.findByEmail(identity.getPrincipal().getName());
            return user != null && user.isResetPassword();
        });
        if (!mustReset) {
            return identity;
        }

        return QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addCredentials(identity.getCredentials())
                .addAttributes(identity.getAttributes())
                .addRole(PASSWORD_RESET_REQUIRED_ROLE)
                .build();
    }
}
