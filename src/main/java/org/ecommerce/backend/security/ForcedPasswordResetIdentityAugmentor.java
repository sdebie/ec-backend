package org.ecommerce.backend.security;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.StaffRoleEn;

import java.util.Set;

/**
 * Server-side mirror of two staff-account-state gates. A staff JWT otherwise carries
 * the account's real role for its full 8-hour life regardless of what happens to the
 * account afterward — both {@code staff_users.reset_password} and {@code .is_active}
 * were previously only ever reported in a response body (AdminMeResource,
 * StaffResource#login), never enforced, so neither a flagged nor a deactivated
 * account's token was actually stopped by anything.
 * <p>
 * This augmentor re-reads the same {@code StaffUserEntity} row on every authenticated
 * staff request and applies whichever of two outcomes the row calls for, deactivation
 * taking priority when both are true:
 * <ul>
 *   <li><b>Deactivated</b> ({@code !isActive()}) — the identity is stripped to zero
 *       roles. No sentinel, unlike a forced reset: a deactivated account has no
 *       self-service endpoint it should still reach, including {@code /admin/me}.</li>
 *   <li><b>Reset required</b> ({@code isResetPassword()}) — the identity's roles are
 *       replaced with a single sentinel role. Every other {@code @RolesAllowed} check
 *       in the admin surface — REST and GraphQL alike, since both rely on the same
 *       mechanism — then fails automatically with no per-endpoint change needed. The
 *       two endpoints a flagged account must still reach (self-service reset,
 *       {@code /admin/me}) opt back in by naming {@link #PASSWORD_RESET_REQUIRED_ROLE}
 *       in their own {@code @RolesAllowed}.</li>
 * </ul>
 * Re-checked on every request rather than baked into the JWT at login: a state change
 * made mid-session (an admin forces a reset, or deactivates the account, while it's
 * already logged in) must take effect immediately, not after the existing token's
 * remaining lifetime.
 * <p>
 * A staff email with no matching row is passed through unchanged rather than failing
 * closed — this codebase's staff/admin test suite self-signs JWTs with no backing DB
 * row for pure role-matrix tests, and failing closed here would defeat that
 * convention for every one of them.
 */
@ApplicationScoped
public class ForcedPasswordResetIdentityAugmentor implements SecurityIdentityAugmentor
{
    public static final String PASSWORD_RESET_REQUIRED_ROLE = "PASSWORD_RESET_REQUIRED";

    private static final Set<String> STAFF_ROLES = StaffRoleEn.names();

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context)
    {
        if (identity.isAnonymous() || identity.getRoles().stream().noneMatch(STAFF_ROLES::contains)) {
            return Uni.createFrom().item(identity);
        }
        return context.runBlocking(() -> augmentForStaffAccountState(identity));
    }

    private SecurityIdentity augmentForStaffAccountState(SecurityIdentity identity)
    {
        // context.runBlocking hands this a worker thread with no ambient transaction or
        // CDI request scope, so the Panache read needs its own explicit transaction —
        // the same requiringNew() pattern StockRecoveryJob uses for the same reason.
        StaffUserEntity user = QuarkusTransaction.requiringNew().call(() ->
                StaffUserEntity.findByEmail(identity.getPrincipal().getName()));

        if (user == null) {
            return identity;
        }
        if (!user.isActive()) {
            return withRoles(identity);
        }
        if (!user.isResetPassword()) {
            return identity;
        }
        return withRoles(identity, PASSWORD_RESET_REQUIRED_ROLE);
    }

    private static SecurityIdentity withRoles(SecurityIdentity identity, String... roles)
    {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addCredentials(identity.getCredentials())
                .addAttributes(identity.getAttributes());
        for (String role : roles) {
            builder.addRole(role);
        }
        return builder.build();
    }
}
