package org.ecommerce.backend.service;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@link CustomerAuthService#generateToken(CustomerEntity)}.
 * Validates shopperType claim correctness (REQ 8.1, 8.2, 11.8).
 *
 * Plain JUnit 5 — the service is instantiated directly with the issuer
 * field set reflectively (no need for a full Quarkus context).
 */
class CustomerAuthServiceTest {

    private CustomerAuthService customerAuthService;

    @BeforeEach
    void setUp() throws Exception {
        customerAuthService = new CustomerAuthService();
        Field issuerField = CustomerAuthService.class.getDeclaredField("issuer");
        issuerField.setAccessible(true);
        issuerField.set(customerAuthService, "http://localhost:8080");
    }

    private CustomerEntity buildCustomer(CustomerTypeEn shopperType) {
        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID();
        user.email = "test-" + UUID.randomUUID() + "@example.com";

        CustomerEntity ce = new CustomerEntity();
        ce.id = UUID.randomUUID();
        ce.user = user;
        ce.shopperType = shopperType;
        return ce;
    }

    private JsonObject decodePayload(String token) {
        String[] parts = token.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return Json.createReader(new StringReader(json)).readObject();
    }

    @Test
    @DisplayName("generateToken with shopperType=null produces claim 'RETAILER' (REQ 8.1, 11.8)")
    void nullShopperType_defaultsToRetailer() {
        CustomerEntity ce = buildCustomer(null);

        String token = customerAuthService.generateToken(ce);
        JsonObject payload = decodePayload(token);

        assertEquals("RETAILER", payload.getString("shopperType"),
                "Null shopperType must default to RETAILER (valid enum name), not GUEST or RETAIL");
    }

    @Test
    @DisplayName("generateToken with shopperType=WHOLESALER produces claim 'WHOLESALER' (REQ 8.2)")
    void wholesalerShopperType_producesWholesalerClaim() {
        CustomerEntity ce = buildCustomer(CustomerTypeEn.WHOLESALER);

        String token = customerAuthService.generateToken(ce);
        JsonObject payload = decodePayload(token);

        assertEquals("WHOLESALER", payload.getString("shopperType"),
                "WHOLESALER customer must produce WHOLESALER claim (frontend maps to WHOLESALE)");
    }
}
