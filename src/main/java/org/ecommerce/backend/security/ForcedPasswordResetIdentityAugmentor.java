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
 * Server-side mirror of two staff-account-state gates: {@code staff_users.reset_password}
 * and {@code .is_active}. Neither is otherwise enforced — a staff JWT carries the
 * account's real role for its full 8-hour life regardless of what happens to the
 * account afterward, so a flagged or deactivated account's token was not actually
 * stopped by anything.
 * <p>
 * Re-reads the same {@code StaffUserEntity} row on every authenticated staff request,
 * deactivation taking priority when both are true:
 * <ul>
 *   <li><b>Deactivated</b> — identity stripped to zero roles. No sentinel, unlike a
 *       forced reset: a deactivated account has no self-service endpoint left to
 *       reach, including {@code /admin/me}.</li>
 *   <li><b>Reset required</b> — roles replaced with a single sentinel role, so every
 *       other {@code @RolesAllowed} check across REST and GraphQL fails automatically.
 *       The two endpoints a flagged account must still reach opt back in by naming
 *       {@link #PASSWORD_RESET_REQUIRED_ROLE} in their own {@code @RolesAllowed}.</li>
 * </ul>
 * Re-checked on every request, not baked into the JWT: a state change made mid-session
 * must take effect immediately, not after the token's remaining lifetime.
 * <p>
 * A staff email with no matching row passes through unchanged rather than failing
 * closed — the staff/admin test suite self-signs JWTs with no backing DB row for pure
 * role-matrix tests, and failing closed here would defeat that convention.
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
