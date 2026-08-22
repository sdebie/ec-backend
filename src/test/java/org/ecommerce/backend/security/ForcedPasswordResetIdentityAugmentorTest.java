package org.ecommerce.backend.security;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.ecommerce.common.entity.StaffUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ForcedPasswordResetIdentityAugmentor's decision logic, run through
 * its public augment() method with a synchronous fake AuthenticationRequestContext
 * (no real Quarkus security pipeline involved — that end-to-end wiring is proven
 * separately by ForcedPasswordResetEnforcementIT).
 */
@QuarkusTest
class ForcedPasswordResetIdentityAugmentorTest
{
    private static final AuthenticationRequestContext SYNC_CONTEXT = supplier -> Uni.createFrom().item(supplier.get());

    private final ForcedPasswordResetIdentityAugmentor augmentor = new ForcedPasswordResetIdentityAugmentor();

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(StaffUserEntity.class);
    }

    private static SecurityIdentity staffIdentity(String email, String role)
    {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal((Principal) () -> email)
                .addRole(role)
                .build();
    }

    private static StaffUserEntity staffRow(boolean resetPassword)
    {
        StaffUserEntity user = new StaffUserEntity();
        user.setResetPassword(resetPassword);
        return user;
    }

    private static StaffUserEntity inactiveStaffRow(boolean resetPassword)
    {
        StaffUserEntity user = staffRow(resetPassword);
        user.setActive(false);
        return user;
    }

    private SecurityIdentity augment(SecurityIdentity input)
    {
        return augmentor.augment(input, SYNC_CONTEXT).await().indefinitely();
    }

    @Test
    void notFlagged_returnsIdentityUnchanged()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(staffRow(false));

        SecurityIdentity input = staffIdentity("staff@test.com", "SUPER_ADMIN");
        SecurityIdentity result = augment(input);

        assertEquals(input.getRoles(), result.getRoles());
        assertTrue(result.hasRole("SUPER_ADMIN"));
        assertFalse(result.hasRole(ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE));
    }

    @Test
    void flagged_stripsRealRoleAndAddsSentinelOnly()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(staffRow(true));

        SecurityIdentity result = augment(staffIdentity("staff@test.com", "SUPER_ADMIN"));

        assertFalse(result.hasRole("SUPER_ADMIN"), "The real role must not survive while flagged");
        assertTrue(result.hasRole(ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE));
        assertEquals(1, result.getRoles().size(), "Only the sentinel role should remain");
    }

    @Test
    void unknownStaffEmail_passesThroughUnchanged()
    {
        // Deliberately NOT fail-closed: this codebase's staff/admin test suite self-signs
        // JWTs with no backing DB row for pure role-matrix tests, and failing closed here
        // would defeat that convention for every one of them.
        when(StaffUserEntity.findByEmail("ghost@test.com")).thenReturn(null);

        SecurityIdentity input = staffIdentity("ghost@test.com", "SUPER_ADMIN");
        SecurityIdentity result = augment(input);

        assertTrue(result.hasRole("SUPER_ADMIN"));
        assertFalse(result.hasRole(ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE));
    }

    @Test
    void deactivated_stripsAllRoles()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(inactiveStaffRow(false));

        SecurityIdentity result = augment(staffIdentity("staff@test.com", "SUPER_ADMIN"));

        assertFalse(result.hasRole("SUPER_ADMIN"), "The real role must not survive deactivation");
        assertFalse(result.hasRole(ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE),
                "Deactivation gets no sentinel role either — there is no self-service endpoint it should still reach");
        assertTrue(result.getRoles().isEmpty(), "A deactivated account keeps no roles at all");
    }

    @Test
    void deactivatedAndFlaggedForReset_deactivationWinsOverTheSentinel()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(inactiveStaffRow(true));

        SecurityIdentity result = augment(staffIdentity("staff@test.com", "SUPER_ADMIN"));

        assertTrue(result.getRoles().isEmpty(),
                "Deactivation must win outright, not downgrade to the reset-required sentinel");
    }

    @Test
    void preservesPrincipalIdentityWhenDeactivated()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(inactiveStaffRow(false));

        SecurityIdentity result = augment(staffIdentity("staff@test.com", "SUPER_ADMIN"));

        assertEquals("staff@test.com", result.getPrincipal().getName());
    }

    @Test
    void preservesPrincipalIdentityWhenFlagged()
    {
        when(StaffUserEntity.findByEmail("staff@test.com")).thenReturn(staffRow(true));

        SecurityIdentity result = augment(staffIdentity("staff@test.com", "SUPER_ADMIN"));

        assertEquals("staff@test.com", result.getPrincipal().getName(),
                "Self-service reset relies on the principal surviving augmentation");
    }

    @Test
    void customerIdentity_passesThroughWithoutAnyStaffLookup()
    {
        SecurityIdentity customerIdentity = staffIdentity("shopper@test.com", "customer");

        SecurityIdentity result = augment(customerIdentity);

        assertSame(customerIdentity, result);
        PanacheMock.verify(StaffUserEntity.class, never()).findByEmail(anyString());
    }

    @Test
    void anonymousIdentity_passesThroughWithoutAnyStaffLookup()
    {
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build();

        SecurityIdentity result = augment(anonymous);

        assertSame(anonymous, result);
        PanacheMock.verify(StaffUserEntity.class, never()).findByEmail(anyString());
    }

    @Test
    void everyStaffRole_isRecognisedAsStaffForTheLookup()
    {
        for (String role : new String[]{"SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER"}) {
            when(StaffUserEntity.findByEmail(role + "@test.com")).thenReturn(staffRow(true));

            SecurityIdentity result = augment(staffIdentity(role + "@test.com", role));

            assertTrue(result.hasRole(ForcedPasswordResetIdentityAugmentor.PASSWORD_RESET_REQUIRED_ROLE),
                    role + " should be treated as a staff role and looked up");
        }
    }
}
