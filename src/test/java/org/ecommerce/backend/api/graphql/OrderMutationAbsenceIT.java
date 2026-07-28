package org.ecommerce.backend.api.graphql;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Permanent regression guard: order creation must never be exposed over GraphQL.
 * <p>
 * A {@code createOrder} mutation used to live on {@link OrderResource}. It was
 * unauthenticated and mapped the client's {@code unitPrice} onto each line and
 * the client's {@code totalAmount} onto the order, so any caller could persist
 * an order at a price of their own choosing — the platform's one real breach of
 * "the backend computes, the client selects".
 * <p>
 * Order creation is REST-only ({@code POST /api/orders}), where the request body
 * carries nothing but {@code variantId} and {@code quantity} and every line is
 * priced server-side from the signature-verified {@code shopperType} claim. That
 * endpoint is deliberately open to anonymous callers so guests can check out, so
 * authentication cannot be the control here — removing the price-carrying surface
 * is. Reintroducing a mutation that accepts prices would silently reopen the hole,
 * which is what this test exists to prevent.
 */
@QuarkusTest
class OrderMutationAbsenceIT
{
    private static final String SCHEMA_PATH = "/api/graphql/schema.graphql";

    @Test
    @DisplayName("the published SDL declares no createOrder mutation")
    void schemaHasNoCreateOrderMutation()
    {
        given()
                .when().get(SCHEMA_PATH)
                .then()
                .statusCode(200)
                .body(not(containsString("createOrder")));
    }

    @Test
    @DisplayName("the published SDL declares no price-carrying order input types")
    void schemaHasNoOrderInputTypes()
    {
        given()
                .when().get(SCHEMA_PATH)
                .then()
                .statusCode(200)
                .body(not(containsString("OrderDtoInput")))
                .body(not(containsString("OrderItemDtoInput")));
    }

    @Test
    @DisplayName("calling createOrder is rejected as an unknown field, not executed")
    void createOrderMutationIsNotExecutable()
    {
        String mutation = """
                {"query":"mutation { createOrder(order: {sessionId: \\"%s\\", totalAmount: 0.01}) { id } }"}
                """.formatted("11111111-1111-1111-1111-111111111111");

        given()
                .contentType("application/json")
                .body(mutation)
                .when().post("/api/graphql")
                .then()
                .statusCode(200)
                // A validation error naming the field proves the schema rejects it
                // before any resolver runs; an empty `errors` array would mean it ran.
                .body("errors", notNullValue())
                .body("errors[0].message", containsString("createOrder"))
                .body("data", nullValue());
    }
}
