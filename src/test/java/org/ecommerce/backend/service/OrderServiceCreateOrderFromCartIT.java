package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DB-backed integration tests for {@link OrderService#createOrderFromCart}.
 * <p>
 * PanacheMock cannot exercise the atomic conditional UPDATE in {@code reserveStock()} —
 * that is a real database property (durability + WHERE-clause-guarded concurrency
 * safety), not branch logic — so these tests run against the real datasource inside a
 * rolled-back {@link TestTransaction}, following the pattern in {@link FeaturedProductServiceIT}.
 * <p>
 * Covers: customer linking (Requirement: signed-in checkout populates
 * {@code customerEntity}, guest checkout leaves it null), and real stock reservation
 * (aggregation of duplicate variant lines, atomic decrement, and whole-request
 * rollback on oversell).
 */
@QuarkusTest
class OrderServiceCreateOrderFromCartIT
{
    @Inject
    OrderService orderService;

    @Inject
    EntityManager em;

    // ── Fixture builders ────────────────────────────────────────────────────

    private ProductEntity newProduct(String marker)
    {
        ProductEntity p = new ProductEntity();
        p.setName(marker + "-product");
        p.setSlug((marker + "-product-" + UUID.randomUUID()).toLowerCase());
        p.setStatus(ProductStatusEn.ACTIVE);
        p.setProductType(ProductTypeEn.SIMPLE);
        p.persist();
        return p;
    }

    private ProductVariantEntity newVariant(String marker, ProductEntity product, int stock)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku(marker + "-sku-" + UUID.randomUUID());
        v.setStatus(ProductStatusEn.ACTIVE);
        v.setStockQuantity(stock);
        v.persist();
        return v;
    }

    private CustomerEntity newCustomer(String marker)
    {
        UserEntity user = new UserEntity();
        user.setEmail(marker.toLowerCase() + "-" + UUID.randomUUID() + "@test.example");
        user.setPasswordHash("irrelevant-test-hash");
        user.persist();

        CustomerEntity customer = new CustomerEntity();
        customer.setUser(user);
        customer.setFirstName(marker);
        customer.setLastName("Customer");
        customer.setShopperType(CustomerTypeEn.RETAILER);
        customer.setStatus(CustomerStatusEn.ACTIVE);
        customer.persist();
        return customer;
    }

    private OrderCreationRequestDto requestWith(OrderCreationItemDto... items)
    {
        OrderCreationRequestDto request = new OrderCreationRequestDto();
        request.setItems(List.of(items));
        return request;
    }

    private OrderCreationItemDto item(UUID variantId, int quantity)
    {
        OrderCreationItemDto item = new OrderCreationItemDto();
        item.setVariantId(variantId.toString());
        item.setQuantity(quantity);
        return item;
    }

    // ── 1. Authenticated checkout links the order to the customer ──────────

    @Test
    @TestTransaction
    @DisplayName("authenticated checkout persists the order with customerEntity set to the caller")
    void authenticatedCheckout_linksOrderToCustomer()
    {
        String marker = "ZZOFC-CUST-" + UUID.randomUUID().toString().substring(0, 8);
        ProductEntity product = newProduct(marker);
        ProductVariantEntity variant = newVariant(marker, product, 10);
        CustomerEntity customer = newCustomer(marker);
        em.flush();

        OrderCreationRequestDto request = requestWith(item(variant.getId(), 2));

        OrderCheckoutResponseDto response = orderService.createOrderFromCart(request, CustomerTypeEn.RETAILER, customer);
        em.flush();
        em.clear();

        OrderEntity persisted = em.find(OrderEntity.class, UUID.fromString(response.getOrderId()));
        assertNotNull(persisted);
        assertNotNull(persisted.getCustomerEntity(), "order must be linked to the authenticated customer");
        assertEquals(customer.getId(), persisted.getCustomerEntity().getId());
    }

    // ── 2. Guest checkout leaves the order unlinked (no regression) ────────

    @Test
    @TestTransaction
    @DisplayName("guest checkout (customer = null) persists the order with no customerEntity")
    void guestCheckout_leavesOrderUnlinked()
    {
        String marker = "ZZOFC-GUEST-" + UUID.randomUUID().toString().substring(0, 8);
        ProductEntity product = newProduct(marker);
        ProductVariantEntity variant = newVariant(marker, product, 10);
        em.flush();

        OrderCreationRequestDto request = requestWith(item(variant.getId(), 1));

        OrderCheckoutResponseDto response = orderService.createOrderFromCart(request, CustomerTypeEn.GUEST, null);
        em.flush();
        em.clear();

        OrderEntity persisted = em.find(OrderEntity.class, UUID.fromString(response.getOrderId()));
        assertNotNull(persisted);
        assertNull(persisted.getCustomerEntity(), "guest checkout must not gain a customer link");
    }

    // ── 3. Duplicate lines for the same variant are aggregated; oversell rolls back everything ──

    @Test
    @TestTransaction
    @DisplayName("two lines for the same variant are combined; an oversell throws and leaves stock untouched")
    void duplicateVariantLines_aggregateAndRejectOversellWithoutPartialDecrement()
    {
        String marker = "ZZOFC-DUP-" + UUID.randomUUID().toString().substring(0, 8);
        ProductEntity product = newProduct(marker);
        ProductVariantEntity variant = newVariant(marker, product, 5);
        em.flush();

        // Two lines of 3 each = 6 requested against 5 in stock. Independently, each
        // line's "3 <= 5" pre-check would pass; only aggregation catches the oversell.
        OrderCreationRequestDto request = requestWith(
                item(variant.getId(), 3),
                item(variant.getId(), 3));

        assertThrows(UnavailableVariantsException.class,
                () -> orderService.createOrderFromCart(request, CustomerTypeEn.RETAILER, null));

        em.flush();
        em.clear();

        ProductVariantEntity reloaded = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(5, reloaded.getStockQuantity(),
                "stock must be untouched — the whole request rolls back, not just the exception");
    }

    // ── 4. A successful order really decrements the persisted stock row ────

    @Test
    @TestTransaction
    @DisplayName("a successful order decrements the variant's persisted stock by the ordered quantity")
    void successfulOrder_decrementsPersistedStock()
    {
        String marker = "ZZOFC-DEC-" + UUID.randomUUID().toString().substring(0, 8);
        ProductEntity product = newProduct(marker);
        ProductVariantEntity variant = newVariant(marker, product, 10);
        em.flush();

        OrderCreationRequestDto request = requestWith(item(variant.getId(), 4));
        orderService.createOrderFromCart(request, CustomerTypeEn.RETAILER, null);

        em.flush();
        em.clear();

        ProductVariantEntity reloaded = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(6, reloaded.getStockQuantity());
    }

    // ── 5. Sequential exhaustion proves the decrement is real/durable ──────

    @Test
    @TestTransaction
    @DisplayName("a second sequential order sees the first order's decrement and can be correctly refused")
    void sequentialOrders_secondCallSeesFirstCallsDecrement()
    {
        String marker = "ZZOFC-SEQ-" + UUID.randomUUID().toString().substring(0, 8);
        ProductEntity product = newProduct(marker);
        ProductVariantEntity variant = newVariant(marker, product, 5);
        em.flush();

        OrderCreationRequestDto firstRequest = requestWith(item(variant.getId(), 3));
        orderService.createOrderFromCart(firstRequest, CustomerTypeEn.RETAILER, null);
        em.flush();
        em.clear();

        ProductVariantEntity afterFirst = em.find(ProductVariantEntity.class, variant.getId());
        assertEquals(2, afterFirst.getStockQuantity());

        OrderCreationRequestDto secondRequest = requestWith(item(variant.getId(), 3));
        assertThrows(UnavailableVariantsException.class,
                () -> orderService.createOrderFromCart(secondRequest, CustomerTypeEn.RETAILER, null),
                "second order requests 3 against only 2 remaining — must be refused, proving the first decrement persisted");
    }
}
