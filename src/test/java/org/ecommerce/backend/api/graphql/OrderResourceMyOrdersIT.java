package org.ecommerce.backend.api.graphql;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration test for the myOrders GraphQL query.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5
 */
@QuarkusTest
class OrderResourceMyOrdersIT {

    private static final String CUSTOMER_A_EMAIL = "alice@test.com";
    private static final String CUSTOMER_B_EMAIL = "bob@test.com";

    @InjectMock
    OrderService orderService;

    private UUID customerAId;
    private UUID customerBId;

    private CustomerEntity customerA;
    private CustomerEntity customerB;

    // OrderSummaryDto for customer A (newest first)
    private OrderSummaryDto summaryA1; // newest
    private OrderSummaryDto summaryA2; // oldest

    // OrderSummaryDto for customer B
    private OrderSummaryDto summaryB1;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(CustomerEntity.class);

        customerAId = UUID.randomUUID();
        customerBId = UUID.randomUUID();

        // Set up Customer A
        UserEntity userA = new UserEntity();
        userA.id = UUID.randomUUID();
        userA.email = CUSTOMER_A_EMAIL;
        userA.passwordHash = "somehash";
        userA.isActive = true;

        customerA = new CustomerEntity();
        customerA.id = customerAId;
        customerA.user = userA;
        customerA.firstName = "Alice";
        customerA.lastName = "Smith";
        customerA.shopperType = CustomerTypeEn.RETAILER;
        customerA.status = CustomerStatusEn.ACTIVE;
        customerA.addresses = new ArrayList<>();
        userA.customer = customerA;

        // Set up Customer B
        UserEntity userB = new UserEntity();
        userB.id = UUID.randomUUID();
        userB.email = CUSTOMER_B_EMAIL;
        userB.passwordHash = "somehash";
        userB.isActive = true;

        customerB = new CustomerEntity();
        customerB.id = customerBId;
        customerB.user = userB;
        customerB.firstName = "Bob";
        customerB.lastName = "Jones";
        customerB.shopperType = CustomerTypeEn.WHOLESALER;
        customerB.status = CustomerStatusEn.ACTIVE;
        customerB.addresses = new ArrayList<>();
        userB.customer = customerB;

        // Set up OrderSummaryDto for Customer A
        // summaryA1: newest, PAID, 5 items, total 150.00
        summaryA1 = new OrderSummaryDto();
        summaryA1.id = UUID.randomUUID().toString();
        summaryA1.orderDate = "2024-06-15T10:30:00";
        summaryA1.status = "PAID";
        summaryA1.itemCount = 5;
        summaryA1.totalAmount = 150.00;

        // summaryA2: oldest, CREATED, 1 item, total 45.50
        summaryA2 = new OrderSummaryDto();
        summaryA2.id = UUID.randomUUID().toString();
        summaryA2.orderDate = "2024-05-10T08:00:00";
        summaryA2.status = "CREATED";
        summaryA2.itemCount = 1;
        summaryA2.totalAmount = 45.50;

        // summaryB1: belongs to Customer B, DELIVERED, 7 items, total 320.00
        summaryB1 = new OrderSummaryDto();
        summaryB1.id = UUID.randomUUID().toString();
        summaryB1.orderDate = "2024-06-12T14:00:00";
        summaryB1.status = "DELIVERED";
        summaryB1.itemCount = 7;
        summaryB1.totalAmount = 320.00;

        // Mock CustomerEntity.findByEmail for both customers
        when(CustomerEntity.findByEmail(CUSTOMER_A_EMAIL)).thenReturn(customerA);
        when(CustomerEntity.findByEmail(CUSTOMER_B_EMAIL)).thenReturn(customerB);

        // Mock OrderService.getMyOrders for each customer
        when(orderService.getMyOrders(eq(customerAId))).thenReturn(List.of(summaryA1, summaryA2));
        when(orderService.getMyOrders(eq(customerBId))).thenReturn(List.of(summaryB1));
    }

    @Test
    @DisplayName("myOrders returns only the authenticated customer's orders, not others'")
    void myOrders_returnsOnlyAuthenticatedCustomerOrders() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlQuery("{ myOrders { id status itemCount totalAmount orderDate } }"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.myOrders", hasSize(2))
                // Verify summaryA1 (newest) is first
                .body("data.myOrders[0].id", equalTo(summaryA1.id))
                .body("data.myOrders[0].status", equalTo("PAID"))
                .body("data.myOrders[0].itemCount", equalTo(5))
                .body("data.myOrders[0].totalAmount", equalTo(150.0f))
                // Verify summaryA2 (oldest) is second
                .body("data.myOrders[1].id", equalTo(summaryA2.id))
                .body("data.myOrders[1].status", equalTo("CREATED"))
                .body("data.myOrders[1].itemCount", equalTo(1))
                .body("data.myOrders[1].totalAmount", equalTo(45.5f));
    }

    @Test
    @DisplayName("myOrders does not include orders belonging to other customers")
    void myOrders_excludesOtherCustomerOrders() {
        String token = generateCustomerJwt(CUSTOMER_B_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlQuery("{ myOrders { id status } }"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.myOrders", hasSize(1))
                .body("data.myOrders[0].id", equalTo(summaryB1.id))
                .body("data.myOrders[0].status", equalTo("DELIVERED"));
    }

    @Test
    @DisplayName("myOrders returns orders in descending createdAt order (newest first)")
    void myOrders_ordersReturnedNewestFirst() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlQuery("{ myOrders { id orderDate } }"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.myOrders", hasSize(2))
                // summaryA1 (2024-06-15) should come before summaryA2 (2024-05-10)
                .body("data.myOrders[0].id", equalTo(summaryA1.id))
                .body("data.myOrders[0].orderDate", containsString("2024-06-15"))
                .body("data.myOrders[1].id", equalTo(summaryA2.id))
                .body("data.myOrders[1].orderDate", containsString("2024-05-10"));
    }

    @Test
    @DisplayName("myOrders field mapping: id, orderDate, status, itemCount, totalAmount all correct")
    void myOrders_fieldMappingCorrect() {
        // Override mock for this test to return only one order for verification
        when(orderService.getMyOrders(eq(customerAId))).thenReturn(List.of(summaryA1));

        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(graphqlQuery("{ myOrders { id orderDate status itemCount totalAmount } }"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.myOrders", hasSize(1))
                .body("data.myOrders[0].id", equalTo(summaryA1.id))
                .body("data.myOrders[0].orderDate", equalTo("2024-06-15T10:30:00"))
                .body("data.myOrders[0].status", equalTo("PAID"))
                .body("data.myOrders[0].itemCount", equalTo(5))
                .body("data.myOrders[0].totalAmount", equalTo(150.0f));
    }

    @Test
    @DisplayName("myOrders without JWT returns GraphQL error (Unauthorized)")
    void myOrders_withoutJwt_returnsUnauthorized() {
        given()
                .contentType("application/json")
                .body(graphqlQuery("{ myOrders { id } }"))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", not(empty()))
                .body("errors[0].message", containsString("Unauthorized"));
    }

    // ─── Helper Methods ─────────────────────────────────────────────────────

    private String generateCustomerJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    private String graphqlQuery(String query) {
        return "{\"query\": \"" + query.replace("\"", "\\\"") + "\"}";
    }
}
