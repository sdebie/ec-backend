package org.ecommerce.backend.api.graphql;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.service.OrderCapabilityService;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * S2′ — {@code orderStatus(orderId)}, the guest checkout success-page poll
 * (guest-order-authorization Requirement 4). Real-DB, not PanacheMock: the headline
 * case (4.1a) is a genuine status transition, which the mocked bulk
 * {@code OrderEntity.update} would silently no-op.
 */
@QuarkusTest
class OrderStatusQueryIT
{
    @Inject
    EntityManager em;

    @Inject
    OrderCapabilityService orderCapability;

    private UUID orderId;

    @BeforeEach
    void seed()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            OrderEntity order = new OrderEntity();
            order.setSessionId(UUID.randomUUID());
            order.setStatus(OrderStatusEn.CREATED);
            order.setTotalAmount(new BigDecimal("319.00"));
            order.persist();
            orderId = order.getId();
        });
    }

    @AfterEach
    void cleanup()
    {
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("delete from OrderEntity o where o.id = :id").setParameter("id", orderId).executeUpdate());
    }

    @Test
    @DisplayName("a valid token returns status, total and creation time")
    void validToken_returnsStatusFields()
    {
        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType("application/json")
                .body("""
                        {"query": "{ orderStatus(orderId: \\"%s\\") { id status totalAmount createdAt } }"}
                        """.formatted(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.orderStatus.id", equalTo(orderId.toString()))
                .body("data.orderStatus.status", equalTo("CREATED"))
                .body("data.orderStatus.createdAt", notNullValue());
    }

    @Test
    @DisplayName("without a token, the poll is refused (Requirement 1.1/1.2)")
    void noToken_returnsOrderNotFound()
    {
        given()
                .contentType("application/json")
                .body("""
                        {"query": "{ orderStatus(orderId: \\"%s\\") { id status } }"}
                        """.formatted(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", equalTo("Order not found"))
                .body("data.orderStatus", nullValue());
    }

    @Test
    @DisplayName("does not return the customer's email or line items (Requirement 4.3) — the fields don't exist on the type")
    void doesNotExposeCustomerOrItems()
    {
        // A GraphQL query for a field the type doesn't have fails validation before
        // execution — proving these fields are absent from the schema, not merely
        // empty in one response.
        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType("application/json")
                .body("""
                        {"query": "{ orderStatus(orderId: \\"%s\\") { id customer { email } } }"}
                        """.formatted(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue());
    }

    /**
     * The headline case (tasks.md 4.1a): an order the abandoned-order sweep has
     * already cancelled must still be readable by a valid token, so the success page
     * can tell the shopper their order was cancelled rather than showing "Order not
     * found" — indistinguishable, per Requirement 5.1, from a nonexistent order.
     * Requirement 2.8: status is never an input to the authorization decision.
     */
    @Test
    @DisplayName("a SYSTEM_CANCELED order is still readable by a valid token")
    void cancelledOrder_stillReadableByValidToken()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            OrderEntity order = OrderEntity.findById(orderId);
            order.setStatus(OrderStatusEn.SYSTEM_CANCELED);
        });

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType("application/json")
                .body("""
                        {"query": "{ orderStatus(orderId: \\"%s\\") { id status } }"}
                        """.formatted(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.orderStatus.status", equalTo("SYSTEM_CANCELED"));
    }

    @Test
    @DisplayName("a USER_CANCELED order is still readable by a valid token")
    void userCancelledOrder_stillReadableByValidToken()
    {
        QuarkusTransaction.requiringNew().run(() -> {
            OrderEntity order = OrderEntity.findById(orderId);
            order.setStatus(OrderStatusEn.USER_CANCELED);
        });

        given()
                .header("X-Order-Token", orderCapability.mint(orderId))
                .contentType("application/json")
                .body("""
                        {"query": "{ orderStatus(orderId: \\"%s\\") { id status } }"}
                        """.formatted(orderId))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.orderStatus.status", equalTo("USER_CANCELED"));
    }

    @Test
    @DisplayName("orderBySessionId no longer exists")
    void orderBySessionIdRemoved()
    {
        given()
                .contentType("application/json")
                .body("""
                        {"query": "{ orderBySessionId(sessionId: \\"%s\\") { id } }"}
                        """.formatted(UUID.randomUUID()))
                .when()
                .post("/api/graphql")
                .then()
                .statusCode(200)
                .body("errors", notNullValue());
    }
}
