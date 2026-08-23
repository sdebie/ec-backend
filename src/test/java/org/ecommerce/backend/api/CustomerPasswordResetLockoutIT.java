package org.ecommerce.backend.api;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.service.CustomerPasswordResetService;
import org.ecommerce.backend.service.PasswordResetCodePolicy;
import org.ecommerce.backend.service.PasswordResetNotificationService;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * Real-DB proof that the code-verification attempt counter and account-level lockout
 * actually persist across separate HTTP requests. Every prior test of this path
 * ({@code CustomerPasswordResetServiceTest}) uses PanacheMock, which has no real
 * transaction to roll back — so it could not see (and did not see) that throwing
 * {@code PasswordResetLockedException}/{@code InvalidPasswordResetCodeException} from
 * inside a plain {@code @Transactional} method marks the JTA transaction rollback-only
 * by default, silently discarding the attempt-counter/lockout write made just before
 * the throw. Found while building the mirrored staff flow (StaffPasswordResetIT),
 * which hit the identical defect against real persistence.
 */
@QuarkusTest
class CustomerPasswordResetLockoutIT
{
    private static final String EMAIL = "lockout-persistence@test.com";
    private static final String VERIFY_PATH = "/api/customers/password-reset/verify";

    @Inject
    EntityManager em;

    @Inject
    PasswordResetCodePolicy policy;

    @InjectMock
    PasswordResetNotificationService passwordResetNotificationService;

    @BeforeEach
    void setUp()
    {
        doNothing().when(passwordResetNotificationService).sendResetCode(anyString(), anyString(), anyInt());
        deleteByEmail(EMAIL);
    }

    @AfterEach
    void tearDown()
    {
        deleteByEmail(EMAIL);
    }

    @Transactional
    void deleteByEmail(String email)
    {
        UserEntity existing = UserEntity.findByEmail(email);
        if (existing != null) {
            CustomerEntity customer = existing.getCustomer();
            if (customer != null) {
                em.remove(em.contains(customer) ? customer : em.merge(customer));
            }
            UserEntity managed = em.contains(existing) ? existing : em.merge(existing);
            em.remove(managed);
        }
    }

    @Transactional
    void seedCustomerWithLiveCode(String rawCode)
    {
        UserEntity user = new UserEntity();
        user.setEmail(EMAIL);
        user.setPasswordHash(org.ecommerce.backend.utils.CustomerPasswordHashUtil.hash("OriginalPassw0rd!"));
        user.setPasswordResetCodeHash(policy.fingerprint(rawCode));
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().plusMinutes(5));
        UserEntity.persist(user);

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.setShopperType(CustomerTypeEn.RETAILER);
        CustomerEntity.persist(customer);
    }

    @Transactional
    int queryAttempts()
    {
        return UserEntity.findByEmail(EMAIL).getPasswordResetCodeAttempts();
    }

    @Transactional
    OffsetDateTime queryLockedUntil()
    {
        return UserEntity.findByEmail(EMAIL).getPasswordResetCodeLockedUntil();
    }

    @Test
    void thirdWrongAttempt_persistsTheLockoutAcrossSeparateRequests()
    {
        seedCustomerWithLiveCode("482913");

        for (int i = 0; i < 2; i++) {
            given().contentType("application/json")
                    .body("{\"email\":\"" + EMAIL + "\",\"code\":\"000000\"}")
                    .when().post(VERIFY_PATH)
                    .then().statusCode(400);
        }
        assertEquals(2, queryAttempts(),
                "each failed attempt must persist its own increment across separate requests");

        // The 3rd failure must report AND persist the lockout itself.
        given().contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\",\"code\":\"000000\"}")
                .when().post(VERIFY_PATH)
                .then().statusCode(429);

        assertNotNull(queryLockedUntil(), "the lockout set by the triggering attempt must survive its own transaction");
        assertTrue(queryLockedUntil().isAfter(OffsetDateTime.now().plusMinutes(14)));

        // A subsequent request — even with the CORRECT code — must still be rejected.
        given().contentType("application/json")
                .body("{\"email\":\"" + EMAIL + "\",\"code\":\"482913\"}")
                .when().post(VERIFY_PATH)
                .then().statusCode(429);
    }
}
