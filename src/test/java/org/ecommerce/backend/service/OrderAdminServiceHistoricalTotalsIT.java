package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The admin order-detail breakdown must describe the money an order actually charged, not a
 * live re-estimate against whatever the VAT rate and shipping fees happen to be today.
 * <p>
 * Every test here mutates {@code vat_rate_percent} and a shipping method's {@code baseFee}
 * <em>after</em> an order exists, then re-fetches the admin detail for that same order — the
 * two production sites that price an order ({@link OrderService#createOrderFromCart} and
 * {@link OrderService#repriceOrder}) are driven for real rather than hand-built, because the
 * bug is specifically about what each of them persists.
 */
@QuarkusTest
class OrderAdminServiceHistoricalTotalsIT
{
    @Inject
    OrderService orderService;

    @Inject
    OrderAdminService orderAdminService;

    @Inject
    EntityManager em;

    private void setVatRate(String percent)
    {
        StoreSettingsEntity setting = StoreSettingsEntity.findById("vat_rate_percent");
        if (setting == null) {
            setting = new StoreSettingsEntity();
            setting.setKey("vat_rate_percent");
        }
        setting.setValue(percent);
        setting.persist();
        em.flush();
    }

    /** Leaves exactly one active shipping method in place, with a known fee. */
    private ShippingMethodEntity soleActiveShippingMethod(String name, String fee)
    {
        ShippingMethodEntity.update("isActive = false where isActive = true");
        em.flush();

        ShippingMethodEntity method = new ShippingMethodEntity();
        method.setName(name);
        method.setActive(true);
        method.setBaseFee(new BigDecimal(fee));
        method.setRequiresAddress(false);
        method.persist();
        em.flush();
        return method;
    }

    private ProductVariantEntity newPricedVariant(String marker, String retailPrice)
    {
        ProductEntity product = new ProductEntity();
        product.setName(marker + " Product");
        product.setSlug((marker + "-" + UUID.randomUUID()).toLowerCase());
        product.setStatus(ProductStatusEn.ACTIVE);
        product.setProductType(ProductTypeEn.SIMPLE);
        product.persist();

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProduct(product);
        variant.setSku(marker + "-sku-" + UUID.randomUUID());
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(50);
        variant.persist();

        VariantPricesEntity price = new VariantPricesEntity();
        price.setVariant(variant);
        price.setPriceType(PriceTypeEn.RETAIL_PRICE);
        price.setPrice(new BigDecimal(retailPrice));
        price.persist();
        em.flush();

        return variant;
    }

    private static OrderCreationItemDto line(ProductVariantEntity variant, int quantity)
    {
        OrderCreationItemDto item = new OrderCreationItemDto();
        item.setVariantId(variant.getId().toString());
        item.setQuantity(quantity);
        return item;
    }

    private UUID placeOrder(OrderCreationItemDto... lines)
    {
        OrderCreationRequestDto request = new OrderCreationRequestDto();
        request.setItems(new ArrayList<>(List.of(lines)));
        String orderId = orderService
                .createOrderFromCart(request, CustomerTypeEn.RETAILER, null, UUID.randomUUID(), null)
                .getOrderId();
        em.flush();
        return UUID.fromString(orderId);
    }

    private AdminOrderDetailDto detailOf(UUID orderId)
    {
        em.flush();
        em.clear();
        return orderAdminService.adminOrder(orderId);
    }

    @Test
    @TestTransaction
    @DisplayName("a later VAT-rate and shipping-fee change must not move an already-placed order's breakdown")
    void creationTimeBreakdown_survivesLaterSettingsChanges()
    {
        setVatRate("0.10");
        soleActiveShippingMethod("Standard", "50.00");

        ProductVariantEntity variant = newPricedVariant("ZZHIST-CREATE-" + UUID.randomUUID().toString().substring(0, 8), "100.00");
        UUID orderId = placeOrder(line(variant, 2));

        AdminOrderDetailDto before = detailOf(orderId);
        assertEquals(0, new BigDecimal("200.00").compareTo(before.getSubtotal()));
        assertEquals(0, new BigDecimal("20.00").compareTo(before.getVatAmount()),
                "VAT at 10% of the 200.00 subtotal");
        assertEquals(0, new BigDecimal("50.00").compareTo(before.getShippingCost()));
        assertEquals(0, new BigDecimal("270.00").compareTo(before.getGrandTotal()));
        assertSumsToGrandTotal(before);

        // The admin changes both inputs after the order was placed and charged.
        setVatRate("0.25");
        soleActiveShippingMethod("Standard", "999.00");

        AdminOrderDetailDto after = detailOf(orderId);
        assertEquals(0, new BigDecimal("20.00").compareTo(after.getVatAmount()),
                "the VAT actually charged must not follow today's rate");
        assertEquals(0, new BigDecimal("50.00").compareTo(after.getShippingCost()),
                "the shipping fee actually charged must not follow today's fee");
        assertEquals(0, new BigDecimal("270.00").compareTo(after.getGrandTotal()));
        assertSumsToGrandTotal(after);
    }

    @Test
    @TestTransaction
    @DisplayName("repricing for a chosen delivery method pins the new breakdown, not just the new total")
    void repriceTimeBreakdown_survivesLaterSettingsChanges()
    {
        setVatRate("0.10");
        ShippingMethodEntity defaultMethod = soleActiveShippingMethod("Default", "30.00");

        ProductVariantEntity variant = newPricedVariant("ZZHIST-REPRICE-" + UUID.randomUUID().toString().substring(0, 8), "50.00");
        UUID orderId = placeOrder(line(variant, 2));

        // The shopper now chooses a different delivery method at checkout — the same
        // sequence OrderContactResource drives: set the method, then reprice.
        ShippingMethodEntity chosen = new ShippingMethodEntity();
        chosen.setName("Express");
        chosen.setActive(true);
        chosen.setBaseFee(new BigDecimal("75.00"));
        chosen.setRequiresAddress(false);
        chosen.persist();
        em.flush();

        OrderEntity order = em.find(OrderEntity.class, orderId);
        order.setShippingMethod(chosen);
        orderService.repriceOrder(order);
        em.flush();
        em.clear();

        AdminOrderDetailDto before = detailOf(orderId);
        assertEquals(0, new BigDecimal("10.00").compareTo(before.getVatAmount()),
                "VAT at 10% of the 100.00 subtotal");
        assertEquals(0, new BigDecimal("75.00").compareTo(before.getShippingCost()),
                "the chosen method's fee at reprice time, not the default estimate from creation");
        assertEquals(0, new BigDecimal("185.00").compareTo(before.getGrandTotal()));
        assertSumsToGrandTotal(before);

        // detailOf() cleared the persistence context above, so `chosen`/`defaultMethod`
        // are now detached — mutate by id rather than reusing those stale references.
        setVatRate("0.50");
        ShippingMethodEntity.update("baseFee = ?1 where id = ?2", new BigDecimal("500.00"), chosen.getId());
        ShippingMethodEntity.update("baseFee = ?1 where id = ?2", new BigDecimal("500.00"), defaultMethod.getId());
        em.flush();

        AdminOrderDetailDto after = detailOf(orderId);
        assertEquals(0, new BigDecimal("10.00").compareTo(after.getVatAmount()));
        assertEquals(0, new BigDecimal("75.00").compareTo(after.getShippingCost()));
        assertEquals(0, new BigDecimal("185.00").compareTo(after.getGrandTotal()));
        assertSumsToGrandTotal(after);
    }

    private static void assertSumsToGrandTotal(AdminOrderDetailDto detail)
    {
        BigDecimal sum = detail.getSubtotal().add(detail.getVatAmount()).add(detail.getShippingCost());
        assertEquals(0, sum.compareTo(detail.getGrandTotal()),
                "subtotal + VAT + shipping must equal the grand total shown beside it");
    }
}
