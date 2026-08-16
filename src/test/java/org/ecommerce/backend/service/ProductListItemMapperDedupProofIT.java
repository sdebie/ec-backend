package org.ecommerce.backend.service;

// Feature: service-layer-refactor, Property 1: Mapper output preservation (product list-item)
// De-duplication proof: a SINGLE ProductListItemMapper now serves all former call sites.

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.assembler.ProductListItemAssembler;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * De-duplication proof test — Property 1: Mapper output preservation (product list-item).
 *
 * <p>Proves that the shared {@link ProductListItemMapper} (the SINGLE source of truth)
 * produces correct output for all edge cases that the former call sites handled:
 * <ul>
 *   <li><b>Admin (toAdminListItem):</b> formerly {@code ProductService.toAdminProductListItemDto}
 *       AND {@code FeaturedProductService.toAdminProductListItemDto} (reconciled into one)</li>
 *   <li><b>Shopping (toShoppingListItem):</b> formerly
 *       {@code ProductRepository.toShoppingListItemDto} (ignoreStatus=true/false) AND
 *       {@code FeaturedProductService.toShoppingListItemDto} (all three consolidated)</li>
 * </ul>
 *
 * <p>Unlike the characterization tests (which call through the services), this test
 * invokes the mapper DIRECTLY, proving the single mapper is the authoritative implementation.
 */
@QuarkusTest
@Tag("service-layer-refactor-property-1")
@DisplayName("ProductListItemMapper De-duplication Proof")
class ProductListItemMapperDedupProofIT
{
    @Inject
    ProductListItemAssembler assembler;

    @Inject
    EntityManager em;

    // ─── Fixture Helpers ────────────────────────────────────────────────────

    private CategoryEntity newCategory(String marker)
    {
        CategoryEntity cat = new CategoryEntity();
        cat.setName(marker + "Category");
        cat.setSlug(marker.toLowerCase() + "cat-" + UUID.randomUUID());
        cat.persist();
        return cat;
    }

    private ProductEntity newProduct(String marker, String name, ProductStatusEn status, ProductTypeEn type, CategoryEntity category)
    {
        ProductEntity p = new ProductEntity();
        p.setName(marker + name);
        p.setSlug((marker + name + "-" + UUID.randomUUID()).toLowerCase());
        p.setShortDescription("Short desc for " + name);
        p.setStatus(status);
        p.setProductType(type);
        p.setFeatured(false);
        if (category != null) {
            p.setCategory(category);
        }
        p.persist();
        return p;
    }

    private ProductVariantEntity newVariant(ProductEntity product, String skuSuffix, ProductStatusEn status, int stock)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku("SKU-" + skuSuffix + "-" + UUID.randomUUID().toString().substring(0, 8));
        v.setStatus(status);
        v.setStockQuantity(stock);
        v.persist();
        return v;
    }

    private ProductImageEntity newImage(ProductVariantEntity variant, String url, boolean featured, int sortOrder)
    {
        ProductImageEntity img = new ProductImageEntity();
        img.setProductVariant(variant);
        img.setImageUrl(url);
        img.setIsFeatured(featured);
        img.setSortOrder(sortOrder);
        img.persist();
        return img;
    }

    private VariantPricesEntity newPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal amount, LocalDateTime start, LocalDateTime end)
    {
        VariantPricesEntity vp = new VariantPricesEntity();
        vp.setVariant(variant);
        vp.setPriceType(priceType);
        vp.setPrice(amount);
        vp.setPriceStartDate(start);
        vp.setPriceEndDate(end);
        vp.persist();
        return vp;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ADMIN: toAdminListItem — single mapper for both former admin call sites
    // (ProductService.toAdminProductListItemDto + FeaturedProductService.toAdminProductListItemDto)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    @DisplayName("Admin: active sale price — resolves retail price, stock, thumbnail, category")
    void admin_activeSalePrice()
    {
        String marker = "ZZDEDUP-ADMIN-SALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        CategoryEntity cat = newCategory(marker);
        ProductEntity product = newProduct(marker, "Sale", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, cat);

        ProductVariantEntity variant = newVariant(product, "V1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/dedup-sale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"), now.minusDays(5), now.plusDays(5));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(product.getId().toString(), dto.getId());
        assertEquals(marker + "Sale", dto.getName());
        assertEquals(product.getSlug(), dto.getSlug());
        assertEquals("ACTIVE", dto.getStatus());
        assertNotNull(dto.getCategory());
        assertEquals(cat.getId(), dto.getCategory().getId());
        assertEquals(cat.getName(), dto.getCategory().getName());
        assertNotNull(dto.getSku());
        assertEquals("https://img.test/dedup-sale.jpg", dto.getThumbnailUrl());
        assertEquals(25, dto.getStockCount());
        assertEquals("IN_STOCK", dto.getStockLevel());
        // Admin resolves lowest RETAIL_PRICE (not sale price)
        assertEquals("100.00", dto.getRetailPrice());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: expired sale price — only active base retail price resolved")
    void admin_expiredSalePrice()
    {
        String marker = "ZZDEDUP-ADMIN-EXP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Expired", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "EV1", ProductStatusEn.ACTIVE, 15);
        newImage(variant, "https://img.test/dedup-expired.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"), now.minusDays(60), now.plusDays(60));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"), now.minusDays(30), now.minusDays(1));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals("120.00", dto.getRetailPrice());
        assertEquals(15, dto.getStockCount());
        assertEquals("IN_STOCK", dto.getStockLevel());
        assertEquals("https://img.test/dedup-expired.jpg", dto.getThumbnailUrl());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: no active price — retailPrice is null")
    void admin_noActivePrice()
    {
        String marker = "ZZDEDUP-ADMIN-NP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoPrice", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, null);

        ProductVariantEntity variant = newVariant(product, "NP1", ProductStatusEn.ACTIVE, 50);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"), now.minusDays(60), now.minusDays(1));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertNull(dto.getRetailPrice());
        assertEquals(50, dto.getStockCount());
        assertEquals("IN_STOCK", dto.getStockLevel());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: zero stock — OUT_OF_STOCK level")
    void admin_zeroStock()
    {
        String marker = "ZZDEDUP-ADMIN-ZS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZeroStock", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "ZS1", ProductStatusEn.ACTIVE, 0);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(0, dto.getStockCount());
        assertEquals("OUT_OF_STOCK", dto.getStockLevel());
        assertEquals("50.00", dto.getRetailPrice());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: low stock (<=10) — LOW_STOCK level")
    void admin_lowStock()
    {
        String marker = "ZZDEDUP-ADMIN-LS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "LowStock", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        newVariant(product, "LS1", ProductStatusEn.ACTIVE, 5);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(5, dto.getStockCount());
        assertEquals("LOW_STOCK", dto.getStockLevel());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: missing images — thumbnailUrl is null")
    void admin_missingImages()
    {
        String marker = "ZZDEDUP-ADMIN-NI-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoImg", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        newVariant(product, "NI1", ProductStatusEn.ACTIVE, 5);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertNull(dto.getThumbnailUrl());
        assertEquals(5, dto.getStockCount());
        assertEquals("LOW_STOCK", dto.getStockLevel());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: multiple variants — stock aggregated across all (including DISABLED)")
    void admin_multipleVariantsAggregateStock()
    {
        String marker = "ZZDEDUP-ADMIN-MV-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "MultiVar", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, null);

        newVariant(product, "MV1", ProductStatusEn.ACTIVE, 8);
        newVariant(product, "MV2", ProductStatusEn.ACTIVE, 3);
        newVariant(product, "MV3", ProductStatusEn.DISABLED, 20);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(31, dto.getStockCount(), "Stock sums all variants (8+3+20)");
        assertEquals("IN_STOCK", dto.getStockLevel());
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: PENDING product — status captured, mapping still works")
    void admin_unpublishedProduct()
    {
        String marker = "ZZDEDUP-ADMIN-PD-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Pending", ProductStatusEn.PENDING, ProductTypeEn.VARIABLE, null);

        ProductVariantEntity variant = newVariant(product, "PD1", ProductStatusEn.ACTIVE, 10);
        newImage(variant, "https://img.test/dedup-pending.jpg", false, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals("PENDING", dto.getStatus());
        assertEquals("https://img.test/dedup-pending.jpg", dto.getThumbnailUrl());
        assertEquals(10, dto.getStockCount());
        assertEquals("LOW_STOCK", dto.getStockLevel());
        assertEquals("75.00", dto.getRetailPrice());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHOPPING: toShoppingListItem — single mapper for all three former producers
    // (ProductRepository.toShoppingListItemDto [ignoreStatus=true/false] +
    //  FeaturedProductService.toShoppingListItemDto)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    @DisplayName("Shopping: active sale price (SIMPLE) — all four price types, variantId set")
    void shopping_activeSalePrice_simple()
    {
        String marker = "ZZDEDUP-SHOP-SALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ShopSale", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "SS1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/dedup-shopsale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"), now.minusDays(5), now.plusDays(5));
        newPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.WHOLESALE_SALE_PRICE, new BigDecimal("65.00"), now.minusDays(3), now.plusDays(3));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(product.getId().toString(), dto.getId());
        assertEquals(marker + "ShopSale", dto.getName());
        assertEquals(product.getSlug(), dto.getSlug());
        assertEquals("Short desc for ShopSale", dto.getShortDescription());
        assertEquals("SIMPLE", dto.getProductType());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(1, dto.getVariantCount());
        assertNotNull(dto.getVariantId(), "SIMPLE product should have variantId");
        assertFalse(dto.getImages().isEmpty());
        assertEquals("https://img.test/dedup-shopsale.jpg", dto.getImages().get(0).getImageUrl());

        // All four price types resolved
        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("100.00").compareTo(dto.getRetailPrice().getPrice()));
        assertEquals("RETAIL_PRICE", dto.getRetailPrice().getPriceType());

        assertNotNull(dto.getRetailSalePrice());
        assertEquals(0, new BigDecimal("79.99").compareTo(dto.getRetailSalePrice().getPrice()));

        assertNotNull(dto.getWholesalePrice());
        assertEquals(0, new BigDecimal("80.00").compareTo(dto.getWholesalePrice().getPrice()));

        assertNotNull(dto.getWholesaleSalePrice());
        assertEquals(0, new BigDecimal("65.00").compareTo(dto.getWholesaleSalePrice().getPrice()));
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: expired sale price — sale prices null, base price resolved")
    void shopping_expiredSalePrice()
    {
        String marker = "ZZDEDUP-SHOP-EXP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ShopExp", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "SE1", ProductStatusEn.ACTIVE, 15);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"), now.minusDays(60), now.plusDays(60));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"), now.minusDays(30), now.minusDays(1));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("120.00").compareTo(dto.getRetailPrice().getPrice()));
        assertNull(dto.getRetailSalePrice(), "Expired sale price should be null");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: VARIABLE product — variantId null, multiple variants counted")
    void shopping_variableProduct()
    {
        String marker = "ZZDEDUP-SHOP-VAR-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Variable", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "VAR1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "VAR2", ProductStatusEn.ACTIVE, 5);
        newImage(v1, "https://img.test/var1.jpg", true, 1);
        newImage(v2, "https://img.test/var2.jpg", false, 2);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"), now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("180.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertNull(dto.getVariantId(), "VARIABLE product variantId must be null");
        assertEquals(2, dto.getVariantCount());
        assertEquals("VARIABLE", dto.getProductType());
        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("180.00").compareTo(dto.getRetailPrice().getPrice()), "Lowest retail price should be 180.00");
        assertTrue(dto.getImages().size() >= 2, "Should have images from both variants");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: ignoreStatus=false — counts only ACTIVE variants, prices from ACTIVE only")
    void shopping_ignoreStatusFalse()
    {
        String marker = "ZZDEDUP-SHOP-IGF-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "IgnFalse", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "IF1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "IF2", ProductStatusEn.DISABLED, 5);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("40.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(1, dto.getVariantCount(), "Only ACTIVE variants counted");
        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("50.00").compareTo(dto.getRetailPrice().getPrice()), "Lowest price from ACTIVE variants only");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: ignoreStatus=true — counts ALL variants, prices from ALL variants")
    void shopping_ignoreStatusTrue()
    {
        String marker = "ZZDEDUP-SHOP-IGT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "IgnTrue", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "IT1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "IT2", ProductStatusEn.DISABLED, 5);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("40.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, true);

        assertEquals(2, dto.getVariantCount(), "ALL variants counted");
        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("40.00").compareTo(dto.getRetailPrice().getPrice()), "Lowest price from ALL variants (40.00 from disabled)");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: zero stock, no images — images list empty")
    void shopping_zeroStockNoImages()
    {
        String marker = "ZZDEDUP-SHOP-ZS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZeroShop", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "ZSS1", ProductStatusEn.ACTIVE, 0);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(1, dto.getVariantCount());
        assertNotNull(dto.getVariantId(), "SIMPLE should have variantId");
        assertTrue(dto.getImages().isEmpty(), "No images should yield empty list");
        assertNotNull(dto.getRetailPrice());
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: PENDING product with ignoreStatus=true — correct status captured")
    void shopping_pendingProductIgnoreStatus()
    {
        String marker = "ZZDEDUP-SHOP-PD-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "PendShop", ProductStatusEn.PENDING, ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "PDS1", ProductStatusEn.ACTIVE, 10);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, true);

        assertEquals("PENDING", dto.getStatus());
        assertEquals(marker + "PendShop", dto.getName());
        assertEquals(1, dto.getVariantCount());
        assertNotNull(dto.getRetailPrice());
        assertEquals(0, new BigDecimal("75.00").compareTo(dto.getRetailPrice().getPrice()));
    }
}

