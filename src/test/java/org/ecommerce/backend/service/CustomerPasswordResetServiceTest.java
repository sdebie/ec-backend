package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.utils.CustomerPasswordHashUtil;
import org.ecommerce.backend.utils.PasswordHashUtil;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class CustomerPasswordResetServiceTest
{
    private static final String EMAIL = "shopper@example.com";
    private static final String RESET_CODE = "123456";
    private static final String CLIENT_IP = "203.0.113.10";

    @Inject
    CustomerPasswordResetService customerPasswordResetService;

    @InjectMock
    PasswordResetNotificationService passwordResetNotificationService;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(UserEntity.class);
    }

    private static UserEntity userWithValidResetCode(CustomerEntity customer)
    {
        UserEntity user = new UserEntity();
        user.setEmail(EMAIL);
        user.setPasswordHash("");
        user.setPasswordResetCodeHash(PasswordHashUtil.hash(RESET_CODE));
        user.setPasswordResetCodeExpiry(OffsetDateTime.now().plusMinutes(5));
        user.setCustomer(customer);
        if (customer != null) {
            customer.setUser(user);
        }
        return user;
    }

    private static CustomerEntity customerOf(CustomerTypeEn shopperType, CustomerStatusEn status)
    {
        CustomerEntity customer = new CustomerEntity();
        customer.setShopperType(shopperType);
        customer.setStatus(status);
        return customer;
    }

    // ── completePasswordResetWithCode ─────────────────────────────────────────

    @Test
    void completeWithCode_guestCustomer_upgradesToRetailer()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.GUEST, CustomerStatusEn.PENDING);
        UserEntity user = userWithValidResetCode(customer);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP);

        assertEquals(CustomerTypeEn.RETAILER, customer.getShopperType());
        assertEquals(CustomerStatusEn.ACTIVE, customer.getStatus());
        assertTrue(user.getPasswordHash().startsWith("$2"), "Password reset must write a BCrypt hash, not the legacy SHA-256 format");
        assertTrue(CustomerPasswordHashUtil.verify("newPassword1!", user.getPasswordHash()));
    }

    @Test
    void completeWithCode_nullShopperType_upgradesToRetailer()
    {
        CustomerEntity customer = customerOf(null, CustomerStatusEn.PENDING);
        UserEntity user = userWithValidResetCode(customer);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP);

        assertEquals(CustomerTypeEn.RETAILER, customer.getShopperType());
    }

    @Test
    void completeWithCode_wholesalerCustomer_isNotDowngraded()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.WHOLESALER, CustomerStatusEn.ACTIVE);
        UserEntity user = userWithValidResetCode(customer);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP);

        assertEquals(CustomerTypeEn.WHOLESALER, customer.getShopperType());
        assertEquals(CustomerStatusEn.ACTIVE, customer.getStatus());
    }

    @Test
    void completeWithCode_retailerCustomer_staysRetailer()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.RETAILER, CustomerStatusEn.ACTIVE);
        UserEntity user = userWithValidResetCode(customer);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP);

        assertEquals(CustomerTypeEn.RETAILER, customer.getShopperType());
    }

    @Test
    void completeWithCode_userWithoutCustomer_doesNotThrow()
    {
        UserEntity user = userWithValidResetCode(null);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        assertDoesNotThrow(() -> customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "newPassword1!", CLIENT_IP));
        assertTrue(user.getPasswordHash().startsWith("$2"), "Password reset must write a BCrypt hash, not the legacy SHA-256 format");
        assertTrue(CustomerPasswordHashUtil.verify("newPassword1!", user.getPasswordHash()));
    }

    @Test
    void completeWithCode_weakPassword_throwsAndDoesNotUpdateHash()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.GUEST, CustomerStatusEn.PENDING);
        UserEntity user = userWithValidResetCode(customer);
        when(UserEntity.findByEmail(EMAIL)).thenReturn(user);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> customerPasswordResetService.completePasswordResetWithCode(EMAIL, RESET_CODE, "short", CLIENT_IP));

        assertEquals("Password must be at least 8 characters", ex.getMessage());
        assertEquals("", user.getPasswordHash(), "A rejected weak password must never be hashed or stored");
    }

}
