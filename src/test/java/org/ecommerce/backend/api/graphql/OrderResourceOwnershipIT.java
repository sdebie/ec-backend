package org.ecommerce.backend.api.graphql;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.backend.service.OrderService;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration test for getOrderDetail GraphQL query ownership enforcement.
 * Tests the full HTTP round-trip through the /api/graphql endpoint with JWT-based
 * authentication and ownership checking.
 *
 * Validates: Requirements 3.1, 3.2, 3.3
 */
@QuarkusTest
class OrderResourceOwnershipIT {

    private static final String CUSTOMER_A_EMAIL = "alice@test.com";
    private static final String CUSTOMER_B_EMAIL = "bob@test.com";
    private static final String STAFF_EMAIL = "staff@test.com";

    @InjectMock
    OrderService orderService;

    private CustomerEntity customerA;
    private CustomerEntity customerB;

    private OrderEntity orderA;
    private OrderEntity orderB;

    private OrderDetailRespDto orderDetailA;
    private OrderDetailRespDto orderDetailB;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(CustomerEntity.class);
        PanacheMock.mock(OrderEntity.class);

        // Customer A
        UserEntity userA = new UserEntity();
        userA.id = UUID.randomUUID();
        userA.email = CUSTOMER_A_EMAIL;

        customerA = new CustomerEntity();
        customerA.id = UUID.randomUUID();
        customerA.user = userA;
        customerA.firstName = "Alice";
        customerA.lastName = "Test";
        customerA.shopperType = CustomerTypeEn.RETAILER;
        customerA.status = CustomerStatusEn.ACTIVE;
        customerA.addresses = new ArrayList<>();

        // Customer B
        UserEntity userB = new UserEntity();
        userB.id = UUID.randomUUID();
        userB.email = CUSTOMER_B_EMAIL;

        customerB = new CustomerEntity();
        customerB.id = UUID.randomUUID();
        customerB.user = userB;
        customerB.firstName = "Bob";
        customerB.lastName = "Test";
        customerB.shopperType = CustomerTypeEn.RETAILER;
        customerB.status = CustomerStatusEn.ACTIVE;
        customerB.addresses = new ArrayList<>();

        // Order A belongs to Customer A
        orderA = new OrderEntity();
        orderA.id = UUID.randomUUID();
        orderA.customerEntity = customerA;
        orderA.totalAmount = new BigDecimal("100.00");
        orderA.status = OrderStatusEn.PAID;
        orderA.createdAt = LocalDateTime.now();
        orderA.items = new ArrayList<>();

        // Order B belongs to Customer B
        orderB = new OrderEntity();
        orderB.id = UUID.randomUUID();
        orderB.customerEntity = customerB;
        orderB.totalAmount = new BigDecimal("250.00");
        orderB.status = OrderStatusEn.PENDING;
        orderB.createdAt = LocalDateTime.now();
        orderB.items = new ArrayList<>();

        // OrderDetailRespDto for order A
        orderDetailA = new OrderDetailRespDto();
        orderDetailA.id = orderA.id;
        orderDetailA.totalAmount = orderA.totalAmount;
        orderDetailA.status = orderA.status;
        orderDetailA.createdAt = orderA.createdAt;

        // OrderDetailRespDto for order B
        orderDetailB = new OrderDetailRespDto();
        orderDetailB.id = orderB.id;
        orderDetailB.totalAmount = orderB.totalAmount;
        orderDetailB.status = orderB.status;
        orderDetailB.createdAt = orderB.createdAt;

        // Mock OrderService.getOrderDetail
        when(orderService.getOrderDetail(orderA.id)).thenReturn(orderDetailA);
        when(orderService.getOrderDetail(orderB.id)).thenReturn(orderDetailB);

        // Mock CustomerEntity.findByEmail using PanacheMock
        mockFindByEmail(CUSTOMER_A_EMAIL, customerA);
        mockFindByEmail(CUSTOMER_B_EMAIL, customerB);

        // Mock OrderEntity.findById
        when(OrderEntity.findById(orderA.id)).thenReturn(orderA);
        when(OrderEntity.findById(orderB.id)).thenReturn(orderB);
    }

    private void mockFindByEmail(String email, CustomerEntity result) {
        when(CustomerEntity.findByEmail(email)).thenReturn(result);
    }

    private String generateCustomerJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    private String generateStaffJwt(String email) {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("staff")
                .sign();
    }

    private String buildGetOrderDetailQuery(String orderId) {
        return """
                {
                    "query": "query { getOrderDetail(id: \\"%s\\") { id totalAmount status } }"
                }
                """.formatted(orderId);
    }

    @Test
    @DisplayName("Customer JWT for own order returns order data successfully")
    void customerJwt_ownOrder_returnsSuccess() {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.id.toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.getOrderDetail.id", equalTo(orderA.id.toString()))
                .body("data.getOrderDetail.totalAmount", notNullValue())
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("Customer JWT for other customer's order returns 'Order not found' error")
    void customerJwt_otherCustomerOrder_returnsOrderNotFound() {
        // Customer A tries to access Customer B's order
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderB.id.toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.getOrderDetail", nullValue());
    }

    @Test
    @DisplayName("Staff JWT returns order data regardless of ownership")
    void staffJwt_anyOrder_returnsSuccess() {
        String token = generateStaffJwt(STAFF_EMAIL);

        // Staff accessing Customer A's order — should succeed
        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.id.toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.getOrderDetail.id", equalTo(orderA.id.toString()))
                .body("errors", nullValue());

        // Staff accessing Customer B's order — should also succeed
        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderB.id.toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.getOrderDetail.id", equalTo(orderB.id.toString()))
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("No JWT (anonymous) returns order data for backwards compatibility")
    void noJwt_returnsSuccess() {
        given()
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.id.toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.getOrderDetail.id", equalTo(orderA.id.toString()))
                .body("errors", nullValue());
    }
}
