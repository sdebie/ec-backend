package org.ecommerce.backend.service;

// Feature: service-layer-refactor, Property 1: Mapper output preservation (product list-item)
// De-duplication proof: a SINGLE ProductListItemMapper now serves all former call sites.
// Validates: Requirements 2.2, 2.4, 4.1, 4.2

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.backend.assembler.ProductListItemAssembler;
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
class ProductListItemMapperDedupProofIT {

    @Inject
    ProductListItemAssembler assembler;

    @Inject
    EntityManager em;

    // ─── Fixture Helpers ────────────────────────────────────────────────────

    private CategoryEntity newCategory(String marker) {
        CategoryEntity cat = new CategoryEntity();
        cat.name = marker + "Category";
        cat.slug = marker.toLowerCase() + "cat-" + UUID.randomUUID();
        cat.persist();
        return cat;
    }

    private ProductEntity newProduct(String marker, String name, ProductStatusEn status,
                                     ProductTypeEn type, CategoryEntity category) {
        ProductEntity p = new ProductEntity();
        p.name = marker + name;
        p.slug = (marker + name + "-" + UUID.randomUUID()).toLowerCase();
        p.shorDescription = "Short desc for " + name;
        p.status = status;
        p.productType = type;
        p.isFeatured = false;
        if (category != null) {
            p.setCategory(category);
        }
        p.persist();
        return p;
    }

    private ProductVariantEntity newVariant(ProductEntity product, String skuSuffix,
                                            ProductStatusEn status, int stock) {
        ProductVariantEntity v = new ProductVariantEntity();
        v.product = product;
        v.sku = "SKU-" + skuSuffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        v.status = status;
        v.stockQuantity = stock;
        v.persist();
        return v;
    }

    private ProductImageEntity newImage(ProductVariantEntity variant, String url,
                                        boolean featured, int sortOrder) {
        ProductImageEntity img = new ProductImageEntity();
        img.productVariant = variant;
        img.imageUrl = url;
        img.isFeatured = featured;
        img.sortOrder = sortOrder;
        img.persist();
        return img;
    }

    private VariantPricesEntity newPrice(ProductVariantEntity variant, PriceTypeEn priceType,
                                          BigDecimal amount, LocalDateTime start, LocalDateTime end) {
        VariantPricesEntity vp = new VariantPricesEntity();
        vp.variant = variant;
        vp.priceType = priceType;
        vp.price = amount;
        vp.priceStartDate = start;
        vp.priceEndDate = end;
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
    void admin_activeSalePrice() {
        String marker = "ZZDEDUP-ADMIN-SALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        CategoryEntity cat = newCategory(marker);
        ProductEntity product = newProduct(marker, "Sale", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, cat);

        ProductVariantEntity variant = newVariant(product, "V1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/dedup-sale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"),
                now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"),
                now.minusDays(5), now.plusDays(5));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(product.id.toString(), dto.id);
        assertEquals(marker + "Sale", dto.name);
        assertEquals(product.slug, dto.slug);
        assertEquals("ACTIVE", dto.status);
        assertNotNull(dto.category);
        assertEquals(cat.id, dto.category.getId());
        assertEquals(cat.name, dto.category.getName());
        assertNotNull(dto.sku);
        assertEquals("https://img.test/dedup-sale.jpg", dto.thumbnailUrl);
        assertEquals(25, dto.stockCount);
        assertEquals("IN_STOCK", dto.stockLevel);
        // Admin resolves lowest RETAIL_PRICE (not sale price)
        assertEquals("100.00", dto.retailPrice);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: expired sale price — only active base retail price resolved")
    void admin_expiredSalePrice() {
        String marker = "ZZDEDUP-ADMIN-EXP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Expired", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "EV1", ProductStatusEn.ACTIVE, 15);
        newImage(variant, "https://img.test/dedup-expired.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"),
                now.minusDays(60), now.plusDays(60));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"),
                now.minusDays(30), now.minusDays(1));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals("120.00", dto.retailPrice);
        assertEquals(15, dto.stockCount);
        assertEquals("IN_STOCK", dto.stockLevel);
        assertEquals("https://img.test/dedup-expired.jpg", dto.thumbnailUrl);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: no active price — retailPrice is null")
    void admin_noActivePrice() {
        String marker = "ZZDEDUP-ADMIN-NP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoPrice", ProductStatusEn.ACTIVE,
                ProductTypeEn.VARIABLE, null);

        ProductVariantEntity variant = newVariant(product, "NP1", ProductStatusEn.ACTIVE, 50);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"),
                now.minusDays(60), now.minusDays(1));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertNull(dto.retailPrice);
        assertEquals(50, dto.stockCount);
        assertEquals("IN_STOCK", dto.stockLevel);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: zero stock — OUT_OF_STOCK level")
    void admin_zeroStock() {
        String marker = "ZZDEDUP-ADMIN-ZS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZeroStock", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "ZS1", ProductStatusEn.ACTIVE, 0);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(0, dto.stockCount);
        assertEquals("OUT_OF_STOCK", dto.stockLevel);
        assertEquals("50.00", dto.retailPrice);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: low stock (<=10) — LOW_STOCK level")
    void admin_lowStock() {
        String marker = "ZZDEDUP-ADMIN-LS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "LowStock", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        newVariant(product, "LS1", ProductStatusEn.ACTIVE, 5);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(5, dto.stockCount);
        assertEquals("LOW_STOCK", dto.stockLevel);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: missing images — thumbnailUrl is null")
    void admin_missingImages() {
        String marker = "ZZDEDUP-ADMIN-NI-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoImg", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        newVariant(product, "NI1", ProductStatusEn.ACTIVE, 5);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertNull(dto.thumbnailUrl);
        assertEquals(5, dto.stockCount);
        assertEquals("LOW_STOCK", dto.stockLevel);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: multiple variants — stock aggregated across all (including DISABLED)")
    void admin_multipleVariantsAggregateStock() {
        String marker = "ZZDEDUP-ADMIN-MV-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "MultiVar", ProductStatusEn.ACTIVE,
                ProductTypeEn.VARIABLE, null);

        newVariant(product, "MV1", ProductStatusEn.ACTIVE, 8);
        newVariant(product, "MV2", ProductStatusEn.ACTIVE, 3);
        newVariant(product, "MV3", ProductStatusEn.DISABLED, 20);
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals(31, dto.stockCount, "Stock sums all variants (8+3+20)");
        assertEquals("IN_STOCK", dto.stockLevel);
    }

    @Test
    @TestTransaction
    @DisplayName("Admin: PENDING product — status captured, mapping still works")
    void admin_unpublishedProduct() {
        String marker = "ZZDEDUP-ADMIN-PD-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Pending", ProductStatusEn.PENDING,
                ProductTypeEn.VARIABLE, null);

        ProductVariantEntity variant = newVariant(product, "PD1", ProductStatusEn.ACTIVE, 10);
        newImage(variant, "https://img.test/dedup-pending.jpg", false, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        AdminProductListItemDto dto = assembler.buildAdminListItem(product, now);

        assertEquals("PENDING", dto.status);
        assertEquals("https://img.test/dedup-pending.jpg", dto.thumbnailUrl);
        assertEquals(10, dto.stockCount);
        assertEquals("LOW_STOCK", dto.stockLevel);
        assertEquals("75.00", dto.retailPrice);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHOPPING: toShoppingListItem — single mapper for all three former producers
    // (ProductRepository.toShoppingListItemDto [ignoreStatus=true/false] +
    //  FeaturedProductService.toShoppingListItemDto)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    @DisplayName("Shopping: active sale price (SIMPLE) — all four price types, variantId set")
    void shopping_activeSalePrice_simple() {
        String marker = "ZZDEDUP-SHOP-SALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ShopSale", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "SS1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/dedup-shopsale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"),
                now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"),
                now.minusDays(5), now.plusDays(5));
        newPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"),
                now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.WHOLESALE_SALE_PRICE, new BigDecimal("65.00"),
                now.minusDays(3), now.plusDays(3));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(product.id.toString(), dto.id);
        assertEquals(marker + "ShopSale", dto.name);
        assertEquals(product.slug, dto.slug);
        assertEquals("Short desc for ShopSale", dto.shortDescription);
        assertEquals("SIMPLE", dto.productType);
        assertEquals("ACTIVE", dto.status);
        assertEquals(1, dto.variantCount);
        assertNotNull(dto.variantId, "SIMPLE product should have variantId");
        assertFalse(dto.images.isEmpty());
        assertEquals("https://img.test/dedup-shopsale.jpg", dto.images.get(0).imageUrl);

        // All four price types resolved
        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("100.00").compareTo(dto.retailPrice.price));
        assertEquals("RETAIL_PRICE", dto.retailPrice.priceType);

        assertNotNull(dto.retailSalePrice);
        assertEquals(0, new BigDecimal("79.99").compareTo(dto.retailSalePrice.price));

        assertNotNull(dto.wholesalePrice);
        assertEquals(0, new BigDecimal("80.00").compareTo(dto.wholesalePrice.price));

        assertNotNull(dto.wholesaleSalePrice);
        assertEquals(0, new BigDecimal("65.00").compareTo(dto.wholesaleSalePrice.price));
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: expired sale price — sale prices null, base price resolved")
    void shopping_expiredSalePrice() {
        String marker = "ZZDEDUP-SHOP-EXP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ShopExp", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "SE1", ProductStatusEn.ACTIVE, 15);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"),
                now.minusDays(60), now.plusDays(60));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"),
                now.minusDays(30), now.minusDays(1));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("120.00").compareTo(dto.retailPrice.price));
        assertNull(dto.retailSalePrice, "Expired sale price should be null");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: VARIABLE product — variantId null, multiple variants counted")
    void shopping_variableProduct() {
        String marker = "ZZDEDUP-SHOP-VAR-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Variable", ProductStatusEn.ACTIVE,
                ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "VAR1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "VAR2", ProductStatusEn.ACTIVE, 5);
        newImage(v1, "https://img.test/var1.jpg", true, 1);
        newImage(v2, "https://img.test/var2.jpg", false, 2);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"),
                now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("180.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertNull(dto.variantId, "VARIABLE product variantId must be null");
        assertEquals(2, dto.variantCount);
        assertEquals("VARIABLE", dto.productType);
        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("180.00").compareTo(dto.retailPrice.price),
                "Lowest retail price should be 180.00");
        assertTrue(dto.images.size() >= 2, "Should have images from both variants");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: ignoreStatus=false — counts only ACTIVE variants, prices from ACTIVE only")
    void shopping_ignoreStatusFalse() {
        String marker = "ZZDEDUP-SHOP-IGF-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "IgnFalse", ProductStatusEn.ACTIVE,
                ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "IF1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "IF2", ProductStatusEn.DISABLED, 5);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"),
                now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("40.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(1, dto.variantCount, "Only ACTIVE variants counted");
        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("50.00").compareTo(dto.retailPrice.price),
                "Lowest price from ACTIVE variants only");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: ignoreStatus=true — counts ALL variants, prices from ALL variants")
    void shopping_ignoreStatusTrue() {
        String marker = "ZZDEDUP-SHOP-IGT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "IgnTrue", ProductStatusEn.ACTIVE,
                ProductTypeEn.VARIABLE, null);

        ProductVariantEntity v1 = newVariant(product, "IT1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "IT2", ProductStatusEn.DISABLED, 5);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"),
                now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("40.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, true);

        assertEquals(2, dto.variantCount, "ALL variants counted");
        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("40.00").compareTo(dto.retailPrice.price),
                "Lowest price from ALL variants (40.00 from disabled)");
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: zero stock, no images — images list empty")
    void shopping_zeroStockNoImages() {
        String marker = "ZZDEDUP-SHOP-ZS-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZeroShop", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "ZSS1", ProductStatusEn.ACTIVE, 0);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, false);

        assertEquals(1, dto.variantCount);
        assertNotNull(dto.variantId, "SIMPLE should have variantId");
        assertTrue(dto.images.isEmpty(), "No images should yield empty list");
        assertNotNull(dto.retailPrice);
    }

    @Test
    @TestTransaction
    @DisplayName("Shopping: PENDING product with ignoreStatus=true — correct status captured")
    void shopping_pendingProductIgnoreStatus() {
        String marker = "ZZDEDUP-SHOP-PD-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "PendShop", ProductStatusEn.PENDING,
                ProductTypeEn.SIMPLE, null);

        ProductVariantEntity variant = newVariant(product, "PDS1", ProductStatusEn.ACTIVE, 10);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"),
                now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto dto = assembler.buildShoppingListItem(product, now, true);

        assertEquals("PENDING", dto.status);
        assertEquals(marker + "PendShop", dto.name);
        assertEquals(1, dto.variantCount);
        assertNotNull(dto.retailPrice);
        assertEquals(0, new BigDecimal("75.00").compareTo(dto.retailPrice.price));
    }
}

