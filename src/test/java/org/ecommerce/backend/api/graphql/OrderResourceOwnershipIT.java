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
import org.ecommerce.common.repository.CustomerRepository;
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
 * <p>
 */
@QuarkusTest
class OrderResourceOwnershipIT
{
    private static final String CUSTOMER_A_EMAIL = "alice@test.com";
    private static final String CUSTOMER_B_EMAIL = "bob@test.com";
    private static final String STAFF_EMAIL = "staff@test.com";

    @InjectMock
    OrderService orderService;

    @InjectMock
    CustomerRepository customerRepository;

    private OrderEntity orderA;
    private OrderEntity orderB;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);

        // Customer A
        UserEntity userA = new UserEntity();
        userA.setId(UUID.randomUUID());
        userA.setEmail(CUSTOMER_A_EMAIL);

        CustomerEntity customerA = new CustomerEntity();
        customerA.setId(UUID.randomUUID());
        customerA.setUser(userA);
        customerA.setFirstName("Alice");
        customerA.setLastName("Test");
        customerA.setShopperType(CustomerTypeEn.RETAILER);
        customerA.setStatus(CustomerStatusEn.ACTIVE);
        customerA.setAddresses(new ArrayList<>());

        // Customer B
        UserEntity userB = new UserEntity();
        userB.setId(UUID.randomUUID());
        userB.setEmail(CUSTOMER_B_EMAIL);

        CustomerEntity customerB = new CustomerEntity();
        customerB.setId(UUID.randomUUID());
        customerB.setUser(userB);
        customerB.setFirstName("Bob");
        customerB.setLastName("Test");
        customerB.setShopperType(CustomerTypeEn.RETAILER);
        customerB.setStatus(CustomerStatusEn.ACTIVE);
        customerB.setAddresses(new ArrayList<>());

        // Order A belongs to Customer A
        orderA = new OrderEntity();
        orderA.setId(UUID.randomUUID());
        orderA.setCustomerEntity(customerA);
        orderA.setTotalAmount(new BigDecimal("100.00"));
        orderA.setStatus(OrderStatusEn.PAID);
        orderA.setCreatedAt(LocalDateTime.now());
        orderA.setItems(new ArrayList<>());

        // Order B belongs to Customer B
        orderB = new OrderEntity();
        orderB.setId(UUID.randomUUID());
        orderB.setCustomerEntity(customerB);
        orderB.setTotalAmount(new BigDecimal("250.00"));
        orderB.setStatus(OrderStatusEn.PENDING);
        orderB.setCreatedAt(LocalDateTime.now());
        orderB.setItems(new ArrayList<>());

        // OrderDetailRespDto for order A
        OrderDetailRespDto orderDetailA = new OrderDetailRespDto();
        orderDetailA.setId(orderA.getId());
        orderDetailA.setTotalAmount(orderA.getTotalAmount());
        orderDetailA.setStatus(orderA.getStatus());
        orderDetailA.setCreatedAt(orderA.getCreatedAt());

        // OrderDetailRespDto for order B
        OrderDetailRespDto orderDetailB = new OrderDetailRespDto();
        orderDetailB.setId(orderB.getId());
        orderDetailB.setTotalAmount(orderB.getTotalAmount());
        orderDetailB.setStatus(orderB.getStatus());
        orderDetailB.setCreatedAt(orderB.getCreatedAt());

        // Mock OrderService.getOrderDetail
        when(orderService.getOrderDetail(orderA.getId())).thenReturn(orderDetailA);
        when(orderService.getOrderDetail(orderB.getId())).thenReturn(orderDetailB);

        // Mock CustomerRepository.findByEmail
        mockFindByEmail(CUSTOMER_A_EMAIL, customerA);
        mockFindByEmail(CUSTOMER_B_EMAIL, customerB);

        // Mock OrderEntity.findById
        when(OrderEntity.findById(orderA.getId())).thenReturn(orderA);
        when(OrderEntity.findById(orderB.getId())).thenReturn(orderB);
    }

    private void mockFindByEmail(String email, CustomerEntity result)
    {
        when(customerRepository.findByEmail(email)).thenReturn(result);
    }

    private String generateCustomerJwt(String email)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    private String generateStaffJwt(String email)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("staff")
                .sign();
    }

    private String buildGetOrderDetailQuery(String orderId)
    {
        return """
                {
                    "query": "query { getOrderDetail(id: \\"%s\\") { id totalAmount status } }"
                }
                """.formatted(orderId);
    }

    @Test
    @DisplayName("Customer JWT for own order returns order data successfully")
    void customerJwt_ownOrder_returnsSuccess()
    {
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.getId().toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("data.getOrderDetail.id", equalTo(orderA.getId().toString()))
                .body("data.getOrderDetail.totalAmount", notNullValue())
                .body("errors", nullValue());
    }

    @Test
    @DisplayName("Customer JWT for other customer's order returns 'Order not found' error")
    void customerJwt_otherCustomerOrder_returnsOrderNotFound()
    {
        // Customer A tries to access Customer B's order
        String token = generateCustomerJwt(CUSTOMER_A_EMAIL);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderB.getId().toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.getOrderDetail", nullValue());
    }

    @Test
    @DisplayName("Staff JWT is not a capability or an ownership credential — getOrderDetail refuses it (Requirement 1.6)")
    void staffJwt_anyOrder_returnsOrderNotFound()
    {
        String token = generateStaffJwt(STAFF_EMAIL);

        // Staff holds neither a valid order-capability token nor the order's owner JWT — refused, same as a stranger.
        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.getId().toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.getOrderDetail", nullValue());

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderB.getId().toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.getOrderDetail", nullValue());
    }

    @Test
    @DisplayName("No credential at all refuses a registered customer's order (Requirement 1.1/1.2 — possession of the id is not enough)")
    void noCredential_customerOwnedOrder_returnsOrderNotFound()
    {
        given()
                .contentType("application/json")
                .body(buildGetOrderDetailQuery(orderA.getId().toString()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.getOrderDetail", nullValue());
    }
}
