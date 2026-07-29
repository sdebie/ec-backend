package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
    private static final String RESET_TOKEN = "token-1234";
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

    private static UserEntity userWithValidResetToken(CustomerEntity customer)
    {
        UserEntity user = new UserEntity();
        user.setEmail(EMAIL);
        user.setPasswordHash("");
        user.setResetToken(RESET_TOKEN);
        user.setResetTokenExpiry(OffsetDateTime.now().plusMinutes(5));
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
        assertEquals(PasswordHashUtil.hash("newPassword1!"), user.getPasswordHash());
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
        assertEquals(PasswordHashUtil.hash("newPassword1!"), user.getPasswordHash());
    }

    // ── completePasswordReset (token path) ────────────────────────────────────

    @Test
    void completeWithToken_guestCustomer_upgradesToRetailer()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.GUEST, CustomerStatusEn.PENDING);
        UserEntity user = userWithValidResetToken(customer);
        when(UserEntity.findByResetToken(RESET_TOKEN)).thenReturn(user);

        customerPasswordResetService.completePasswordReset(RESET_TOKEN, "newPassword1!");

        assertEquals(CustomerTypeEn.RETAILER, customer.getShopperType());
        assertEquals(CustomerStatusEn.ACTIVE, customer.getStatus());
        assertNull(user.getResetToken());
        assertEquals(PasswordHashUtil.hash("newPassword1!"), user.getPasswordHash());
    }

    @Test
    void completeWithToken_nullShopperType_upgradesToRetailer()
    {
        CustomerEntity customer = customerOf(null, CustomerStatusEn.PENDING);
        UserEntity user = userWithValidResetToken(customer);
        when(UserEntity.findByResetToken(RESET_TOKEN)).thenReturn(user);

        customerPasswordResetService.completePasswordReset(RESET_TOKEN, "newPassword1!");

        assertEquals(CustomerTypeEn.RETAILER, customer.getShopperType());
    }

    @Test
    void completeWithToken_wholesalerCustomer_isNotDowngraded()
    {
        CustomerEntity customer = customerOf(CustomerTypeEn.WHOLESALER, CustomerStatusEn.ACTIVE);
        UserEntity user = userWithValidResetToken(customer);
        when(UserEntity.findByResetToken(RESET_TOKEN)).thenReturn(user);

        customerPasswordResetService.completePasswordReset(RESET_TOKEN, "newPassword1!");

        assertEquals(CustomerTypeEn.WHOLESALER, customer.getShopperType());
    }
}
