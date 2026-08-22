package org.ecommerce.backend.api.rest;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@QuarkusTest
class OrderContactResourceTest
{
    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(OrderEntity.class);
        PanacheMock.mock(ShippingMethodEntity.class);
        PanacheMock.mock(CustomerEntity.class);
    }

    private String generateCustomerJwt(String email)
    {
        return Jwt.subject(email)
                .issuer("http://localhost:8080")
                .groups("customer")
                .sign();
    }

    /**
     * Mints a token shaped exactly like {@code OrderCapabilityService.mint(orderId)}
     * (design.md §3.1: subject = order id, scope = order-capability). Built directly
     * against the JWT signer rather than through the service, which does not exist
     * until wave 1 — once it does, both constructions sign with the same key and
     * claim shape, so a token minted here is indistinguishable from a real one.
     */
    private String generateOrderToken(UUID orderId)
    {
        return Jwt.issuer("http://localhost:8080")
                .subject(orderId.toString())
                .claim("scope", "order-capability")
                .expiresIn(Duration.ofMinutes(60))
                .sign();
    }

    @Test
    void updateContact_noCredential_returns404AndDoesNotMutate()
    {
        // Requirement 1.1/1.2: possession of the order id alone is no longer enough,
        // for a guest order exactly as for a customer's. This is the test that used to
        // be updateContact_validBody_returns200 and asserted 200 with no credential at
        // all — that assertion is now the vulnerability, not the spec.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);

        ShippingMethodEntity shippingMethod = new ShippingMethodEntity();
        shippingMethod.setId(shippingMethodId);
        shippingMethod.setName("Standard Delivery");
        shippingMethod.setActive(true);
        shippingMethod.setBaseFee(new BigDecimal("89.00"));

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(shippingMethod);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s",
                    "streetAddress": "123 Main St",
                    "city": "Cape Town",
                    "province": "Western Cape",
                    "postalCode": "8001"
                }
                """.formatted(shippingMethodId);

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));

        // Not just the status: a 404 with the write still committed would pass a
        // status-only assertion (design.md task 0.3's own warning).
        assertNull(order.getContactEmail());
    }

    @Test
    void updateContact_validToken_returns200()
    {
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);

        ShippingMethodEntity shippingMethod = new ShippingMethodEntity();
        shippingMethod.setId(shippingMethodId);
        shippingMethod.setName("Standard Delivery");
        shippingMethod.setActive(true);
        shippingMethod.setBaseFee(new BigDecimal("89.00"));

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(shippingMethod);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s",
                    "streetAddress": "123 Main St",
                    "city": "Cape Town",
                    "province": "Western Cape",
                    "postalCode": "8001"
                }
                """.formatted(shippingMethodId);

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("orderId", equalTo(orderId.toString()))
                .body("contactEmail", equalTo("test@example.com"))
                .body("contactFirstName", equalTo("John"))
                .body("contactLastName", equalTo("Doe"))
                .body("shippingMethodId", equalTo(shippingMethodId.toString()))
                .body("streetAddress", equalTo("123 Main St"));
    }

    @Test
    void updateContact_anonymousCaller_customerOwnedOrder_returns404AndDoesNotMutate()
    {
        // The negative case that had never existed anywhere in the suite (design.md
        // §5a, task 0.4): an anonymous caller — no Authorization, no X-Order-Token —
        // acting on an order that belongs to a registered customer.
        UUID orderId = UUID.randomUUID();

        CustomerEntity owner = new CustomerEntity();
        owner.setId(UUID.randomUUID());

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(owner);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "email": "attacker@example.com",
                    "firstName": "New",
                    "lastName": "Address",
                    "streetAddress": "999 Redirect Ave"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));

        assertNull(order.getContactEmail());
    }

    @Test
    void updateContact_invalidOrderId_returns404()
    {
        UUID orderId = UUID.randomUUID();

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(null);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));
    }

    @Test
    void updateContact_customerJwtOwnOrder_returns200()
    {
        UUID orderId = UUID.randomUUID();
        String email = "alice@test.com";

        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(customer);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(CustomerEntity.findByEmail(email)).thenReturn(customer);

        String body = """
                {
                    "email": "alice@test.com",
                    "firstName": "Alice",
                    "lastName": "Test"
                }
                """;

        given()
                .header("Authorization", "Bearer " + generateCustomerJwt(email))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("contactEmail", equalTo("alice@test.com"));
    }

    @Test
    void updateContact_customerJwtOtherCustomersOrder_returns404AndDoesNotMutate()
    {
        // A signed-in customer must not be able to rewrite another customer's
        // in-progress order just by knowing/guessing its ID. Reports as "not
        // found" (not "forbidden") so the endpoint can't be used to enumerate
        // which order IDs exist.
        UUID orderId = UUID.randomUUID();
        String callerEmail = "alice@test.com";

        CustomerEntity caller = new CustomerEntity();
        caller.setId(UUID.randomUUID());

        CustomerEntity owner = new CustomerEntity();
        owner.setId(UUID.randomUUID());

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(owner);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(CustomerEntity.findByEmail(callerEmail)).thenReturn(caller);

        String body = """
                {
                    "email": "attacker@example.com",
                    "firstName": "New",
                    "lastName": "Address",
                    "streetAddress": "999 Redirect Ave"
                }
                """;

        given()
                .header("Authorization", "Bearer " + generateCustomerJwt(callerEmail))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(404)
                .body("error", equalTo("Order not found"));

        assertNull(order.getContactEmail());
    }

    @Test
    void updateContact_orderNotCreated_returns409AndDoesNotMutate()
    {
        // Once an order has left CREATED (e.g. paid via PayFast, or fulfilled/
        // cancelled by staff), contact/address/shipping must be frozen — otherwise
        // this endpoint can silently redirect a paid order's delivery address or
        // rewrite its contact email after the fact.
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.PAID);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "email": "attacker@example.com",
                    "firstName": "New",
                    "lastName": "Address",
                    "streetAddress": "999 Redirect Ave"
                }
                """;

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(409)
                .body("error", equalTo("Order can no longer be modified"));

        assertNull(order.getContactEmail());
        assertEquals(0, order.getTotalAmount().compareTo(new BigDecimal("500.00")));
    }

    @Test
    void updateContact_invalidShippingMethodId_returns422()
    {
        UUID orderId = UUID.randomUUID();
        UUID invalidShippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(invalidShippingMethodId)).thenReturn(null);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s"
                }
                """.formatted(invalidShippingMethodId);

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422)
                .body("error", equalTo("Shipping method not found or inactive"));
    }

    @Test
    void updateContact_selectingShippingMethod_repricesTotalToThatMethodsFee()
    {
        // The order is created before any delivery method exists, so its total
        // carries the DEFAULT estimate. Selecting a method must move the total to
        // that method's fee — otherwise PayFast charges without the delivery.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("115.00")); // stale: created with the default estimate
        order.setStatus(OrderStatusEn.CREATED);
        order.getItems().add(orderItem(new BigDecimal("100.00"), 2));

        ShippingMethodEntity express = new ShippingMethodEntity();
        express.setId(shippingMethodId);
        express.setName("Express Overnight");
        express.setActive(true);
        express.setBaseFee(new BigDecimal("250.00"));

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(express);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s",
                    "streetAddress": "123 Main St",
                    "city": "Cape Town",
                    "province": "Western Cape",
                    "postalCode": "8001"
                }
                """.formatted(shippingMethodId);

        // 200 subtotal + 30 VAT (15%) + 250 delivery = 480
        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("subtotal", comparesEqualTo(200.00f))
                .body("vatAmount", comparesEqualTo(30.00f))
                .body("shippingEstimate", comparesEqualTo(250.00f))
                .body("grandTotal", comparesEqualTo(480.00f))
                .body("totalAmount", comparesEqualTo(480.00f));

        // and the new total is written back to the order the gateway will charge
        assertEquals(0, order.getTotalAmount().compareTo(new BigDecimal("480.00")));
    }

    @Test
    void updateContact_repricedSubtotalComesFromStoredLinePrices()
    {
        // Repricing must never re-price a product — it rebuilds the subtotal from
        // the line prices the server already set for the shopper's tier.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("1.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.getItems().add(orderItem(new BigDecimal("19.99"), 3));
        order.getItems().add(orderItem(new BigDecimal("5.50"), 2));

        ShippingMethodEntity collection = new ShippingMethodEntity();
        collection.setId(shippingMethodId);
        collection.setName("In-Store Pickup");
        collection.setActive(true);
        collection.setBaseFee(BigDecimal.ZERO);
        // Collection: the shopper comes to the store, so no address is required. Stated
        // rather than inferred — a free same-day collection is indistinguishable from a
        // free same-day delivery by fee and lead time alone.
        collection.setRequiresAddress(false);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(collection);

        String body = """
                {
                    "email": "test@example.com",
                    "firstName": "John",
                    "lastName": "Doe",
                    "shippingMethodId": "%s"
                }
                """.formatted(shippingMethodId);

        // (19.99 × 3) + (5.50 × 2) = 70.97; VAT 10.65 (half-up); collection is free.
        // Values are compared as BigDecimal, not against a JSON-typed literal: a
        // zero fee serialises as `0` (Integer) while 70.97 serialises as a
        // Float, so a typed matcher passes on one and fails on the other.
        io.restassured.response.Response response = given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .extract()
                .response();

        assertMoney(response, "subtotal", "70.97");
        assertMoney(response, "vatAmount", "10.65");
        assertMoney(response, "shippingEstimate", "0");
        assertMoney(response, "grandTotal", "81.62");
    }

    /** Compares a JSON money field by value, whatever numeric type it deserialised as. */
    private static void assertMoney(io.restassured.response.Response response, String path, String expected)
    {
        // Bind to Object first: response.path() is generic <T>, so passing it
        // straight into String.valueOf() lets the compiler choose the char[]
        // overload and blow up at runtime.
        Object raw = response.path(path);
        BigDecimal actual = new BigDecimal(String.valueOf(raw));
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                path + " expected " + expected + " but was " + actual);
    }

    private static OrderItemEntity orderItem(BigDecimal unitPrice, int quantity)
    {
        OrderItemEntity item = new OrderItemEntity();
        item.setUnitPrice(unitPrice);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void updateContact_partialBody_preservesPreviouslySavedContactFields()
    {
        // A caller that PATCHes only shippingMethodId — the storefront never does
        // this today (useCheckoutSubmit always sends the full contact block), but
        // nothing stops a different caller from doing so — must not silently null
        // out contact fields an earlier call in the same checkout already saved.
        // The address fields below already guard this via firstNonBlank/null
        // checks; email/firstName/lastName did not.
        UUID orderId = UUID.randomUUID();
        UUID shippingMethodId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setContactEmail("already-saved@example.com");
        order.setContactFirstName("Already");
        order.setContactLastName("Saved");

        ShippingMethodEntity shippingMethod = new ShippingMethodEntity();
        shippingMethod.setId(shippingMethodId);
        shippingMethod.setName("Standard Delivery");
        shippingMethod.setActive(true);
        shippingMethod.setBaseFee(new BigDecimal("89.00"));
        shippingMethod.setRequiresAddress(false);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);
        when(ShippingMethodEntity.findById(shippingMethodId)).thenReturn(shippingMethod);

        String body = """
                {
                    "shippingMethodId": "%s"
                }
                """.formatted(shippingMethodId);

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("contactEmail", equalTo("already-saved@example.com"))
                .body("contactFirstName", equalTo("Already"))
                .body("contactLastName", equalTo("Saved"));

        assertEquals("already-saved@example.com", order.getContactEmail());
        assertEquals("Already", order.getContactFirstName());
        assertEquals("Saved", order.getContactLastName());
    }

    @Test
    void updateContact_guestOrderNoCustomer_returns200()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("250.00"));
        order.setStatus(OrderStatusEn.CREATED);
        order.setCustomerEntity(null); // Guest order — no customer

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "email": "guest@example.com",
                    "firstName": "Jane",
                    "lastName": "Guest"
                }
                """;

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(200)
                .body("orderId", equalTo(orderId.toString()))
                .body("contactEmail", equalTo("guest@example.com"))
                .body("contactFirstName", equalTo("Jane"))
                .body("contactLastName", equalTo("Guest"));
    }

    @Test
    void updateContact_nullBody_returns400()
    {
        // Mirrors OrderResource.createOrder's own null-body guard: a missing
        // request body is malformed, not "well-formed but invalid" — a
        // different problem to the 422s below, so it gets 400 instead.
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body("null")
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(400)
                .body("error", equalTo("Request body is required"));
    }

    @Test
    void updateContact_invalidEmailFormat_returns422AndDoesNotMutate()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "email": "not-an-email"
                }
                """;

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422);

        assertNull(order.getContactEmail());
    }

    @Test
    void updateContact_emailExceedsMaxLength_returns422AndDoesNotMutate()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        // 250-char local part + "@example.com" comfortably clears 254 total.
        String body = """
                {
                    "email": "%s@example.com"
                }
                """.formatted("a".repeat(250));

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422);

        assertNull(order.getContactEmail());
    }

    @Test
    void updateContact_firstNameExceedsMaxLength_returns422AndDoesNotMutate()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "firstName": "%s"
                }
                """.formatted("A".repeat(121));

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422);

        assertNull(order.getContactFirstName());
    }

    @Test
    void updateContact_lastNameExceedsMaxLength_returns422AndDoesNotMutate()
    {
        UUID orderId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatusEn.CREATED);

        when(OrderEntity.findOrderInfoById(orderId)).thenReturn(order);

        String body = """
                {
                    "lastName": "%s"
                }
                """.formatted("B".repeat(121));

        given()
                .header("X-Order-Token", generateOrderToken(orderId))
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .patch("/api/orders/{orderId}/contact", orderId)
                .then()
                .statusCode(422);

        assertNull(order.getContactLastName());
    }
}
