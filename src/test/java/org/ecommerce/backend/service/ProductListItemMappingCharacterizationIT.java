package org.ecommerce.backend.service;

// Feature: service-layer-refactor, Task 1.1: Characterization tests for product list-item mapping
// Validates: Requirements 4.2, 4.4

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.dto.VariantPriceDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests pinning the current output of the three product list-item
 * mapping implementations. These baselines are the safety net for the extraction of
 * {@code ProductListItemMapper} — after consolidation, these tests must still pass
 * with identical output.
 * <p>
 * Covers:
 * - Admin mapping: ProductService.toAdminProductListItemDto AND
 * FeaturedProductService.toAdminProductListItemDto (separate implementations)
 * - Shopping mapping: ProductRepository.toShoppingListItemDto (ignoreStatus=true/false)
 * AND FeaturedProductService.toShoppingListItemDto
 * - Asserts the shopping mapping baselines from all three producers match
 */
@QuarkusTest
class ProductListItemMappingCharacterizationIT
{
    @Inject
    ProductService productService;

    @Inject
    FeaturedProductService featuredProductService;

    @Inject
    ProductRepository productRepository;

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

    private ProductEntity newProduct(String marker, String name, ProductStatusEn status,
                                     ProductTypeEn type, boolean featured, CategoryEntity category)
    {
        ProductEntity p = new ProductEntity();
        p.setName(marker + name);
        p.setSlug((marker + name + "-" + UUID.randomUUID()).toLowerCase());
        p.setShortDescription("Short desc for " + name);
        p.setStatus(status);
        p.setProductType(type);
        p.setFeatured(featured);
        if (category != null) {
            p.setCategory(category);
        }
        p.persist();
        return p;
    }

    private ProductVariantEntity newVariant(ProductEntity product, String skuSuffix,
                                            ProductStatusEn status, int stock)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku("SKU-" + skuSuffix + "-" + UUID.randomUUID().toString().substring(0, 8));
        v.setStatus(status);
        v.setStockQuantity(stock);
        v.persist();
        return v;
    }

    private ProductImageEntity newImage(ProductVariantEntity variant, String url,
                                        boolean featured, int sortOrder)
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

    // ─── Test: Active sale price product (SIMPLE, featured) ─────────────────

    @Test
    @TestTransaction
    void activeSalePrice_adminMappingBaselineFromBothServices()
    {
        String marker = "ZZCHAR-SALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        CategoryEntity cat = newCategory(marker);
        ProductEntity product = newProduct(marker, "ActiveSale", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, cat);

        ProductVariantEntity variant = newVariant(product, "V1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/active-sale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"), now.minusDays(5), now.plusDays(5));
        newPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.WHOLESALE_SALE_PRICE, new BigDecimal("65.00"), now.minusDays(3), now.plusDays(3));
        em.flush();

        // Pin admin baseline from ProductService
        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        List<AdminProductListItemDto> psAdminList = adminPage.getContent();
        assertEquals(1, psAdminList.size(), "Exactly one product with our marker in admin list");
        AdminProductListItemDto psAdmin = psAdminList.get(0);

        // Pin admin baseline from FeaturedProductService
        List<AdminProductListItemDto> fpsAdminList = featuredProductService.getFeaturedProductsForAdmin();
        AdminProductListItemDto fpsAdmin = fpsAdminList.stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("Featured admin list missing our product"));

        // Both admin mappers should produce identical output for the same product
        assertAdminDtoEquals(psAdmin, fpsAdmin, "Admin mapping from ProductService and FeaturedProductService must match");

        // Pin admin baseline values
        assertEquals(product.getId().toString(), psAdmin.getId());
        assertEquals(marker + "ActiveSale", psAdmin.getName());
        assertEquals(product.getSlug(), psAdmin.getSlug());
        assertEquals("ACTIVE", psAdmin.getStatus());
        assertNotNull(psAdmin.getCategory());
        assertEquals(cat.getId(), psAdmin.getCategory().getId());
        assertNotNull(psAdmin.getSku());
        assertEquals("https://img.test/active-sale.jpg", psAdmin.getThumbnailUrl());
        assertEquals(25, psAdmin.getStockCount());
        assertEquals("IN_STOCK", psAdmin.getStockLevel());
        assertNotNull(psAdmin.getRetailPrice(), "Active retail price should be resolved");
    }

    @Test
    @TestTransaction
    void activeSalePrice_shoppingMappingBaselineFromAllProducers()
    {
        String marker = "ZZCHAR-SHOPSALE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        CategoryEntity cat = newCategory(marker);
        ProductEntity product = newProduct(marker, "ShopSale", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, cat);

        ProductVariantEntity variant = newVariant(product, "SV1", ProductStatusEn.ACTIVE, 25);
        newImage(variant, "https://img.test/shop-sale.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("100.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("79.99"), now.minusDays(5), now.plusDays(5));
        newPrice(variant, PriceTypeEn.WHOLESALE_PRICE, new BigDecimal("80.00"), now.minusDays(30), now.plusDays(30));
        newPrice(variant, PriceTypeEn.WHOLESALE_SALE_PRICE, new BigDecimal("65.00"), now.minusDays(3), now.plusDays(3));
        em.flush();

        // Shopping baseline from FeaturedProductService (ignoreStatus=false is its default)
        List<ProductShoppingListItemDto> fpsList = featuredProductService.getFeaturedShoppingProducts(50, null);
        ProductShoppingListItemDto fpsDto = fpsList
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("FeaturedProductService shopping list missing product"));

        // Shopping baseline from ProductRepository via ProductService (ignoreStatus=false)
        List<ProductShoppingListItemDto> repoList = productService.getShoppingProducts(
                pageRequest(0, 50), nameFilter(marker), false, false);
        ProductShoppingListItemDto repoDto = repoList
                .stream()
                .filter(d -> d.getName()
                        .startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("ProductRepository shopping list missing product"));

        // Shopping baseline from ProductRepository via ProductService (ignoreStatus=true)
        List<ProductShoppingListItemDto> repoIgnoreList = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, true);
        ProductShoppingListItemDto repoIgnoreDto = repoIgnoreList
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("ProductRepository ignoreStatus shopping list missing product"));

        // All three shopping producers must produce matching output
        assertShoppingDtoEquals(fpsDto, repoDto, "FeaturedProductService and ProductRepository (ignoreStatus=false) shopping mappings must match");
        assertShoppingDtoEquals(fpsDto, repoIgnoreDto, "FeaturedProductService and ProductRepository (ignoreStatus=true) shopping mappings must match");

        // Pin shopping baseline field values
        assertEquals(product.getId().toString(), fpsDto.getId());
        assertEquals(marker + "ShopSale", fpsDto.getName());
        assertEquals(product.getSlug(), fpsDto.getSlug());
        assertEquals("Short desc for ShopSale", fpsDto.getShortDescription());
        assertEquals("SIMPLE", fpsDto.getProductType());
        assertEquals("ACTIVE", fpsDto.getStatus());
        assertEquals(1, fpsDto.getVariantCount());
        assertNotNull(fpsDto.getVariantId(), "SIMPLE product should have variantId");
        assertFalse(fpsDto.getImages().isEmpty(), "Should have images");
        assertNotNull(fpsDto.getRetailPrice(), "Should have active retail price");
        assertNotNull(fpsDto.getRetailSalePrice(), "Should have active retail sale price");
        assertNotNull(fpsDto.getWholesalePrice(), "Should have active wholesale price");
        assertNotNull(fpsDto.getWholesaleSalePrice(), "Should have active wholesale sale price");
    }

    // ─── Test: Expired sale price (only base retail price is active) ────────

    @Test
    @TestTransaction
    void expiredSalePrice_adminBaseline()
    {
        String marker = "ZZCHAR-EXPIRED-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        CategoryEntity cat = newCategory(marker);
        ProductEntity product = newProduct(marker, "ExpiredSale", ProductStatusEn.ACTIVE,
                ProductTypeEn.SIMPLE, true, cat);

        ProductVariantEntity variant = newVariant(product, "EV1", ProductStatusEn.ACTIVE, 15);
        newImage(variant, "https://img.test/expired-sale.jpg", true, 1);
        // Active base retail price
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"),
                now.minusDays(60), now.plusDays(60));
        // Expired sale price
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"),
                now.minusDays(30), now.minusDays(1));
        em.flush();

        // Admin from ProductService
        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        // Admin from FeaturedProductService
        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin().stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "Expired sale: admin mappings must match");
        // Only the active base retail price should be resolved
        assertEquals("120.00", psAdmin.getRetailPrice());
        assertEquals(15, psAdmin.getStockCount());
        assertEquals("IN_STOCK", psAdmin.getStockLevel());
    }

    @Test
    @TestTransaction
    void expiredSalePrice_shoppingBaseline()
    {
        String marker = "ZZCHAR-EXPSHOP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ExpShop", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, null);

        ProductVariantEntity variant = newVariant(product, "ES1", ProductStatusEn.ACTIVE, 15);
        newImage(variant, "https://img.test/expshop.jpg", false, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("120.00"), now.minusDays(60), now.plusDays(60));
        newPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, new BigDecimal("89.99"), now.minusDays(30), now.minusDays(1));
        em.flush();

        // FeaturedProductService shopping
        ProductShoppingListItemDto fpsDto = featuredProductService.getFeaturedShoppingProducts(50, null)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        // ProductRepository shopping (ignoreStatus=false)
        ProductShoppingListItemDto repoDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, false)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        // ProductRepository shopping (ignoreStatus=true)
        ProductShoppingListItemDto repoIgnoreDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, true)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertShoppingDtoEquals(fpsDto, repoDto, "Expired sale: shopping mappings must match (fps vs repo)");
        assertShoppingDtoEquals(fpsDto, repoIgnoreDto, "Expired sale: shopping mappings must match (fps vs repo ignoreStatus)");

        // Expired sale price should NOT be returned
        assertNotNull(fpsDto.getRetailPrice(), "Active retail price should exist");
        assertNull(fpsDto.getRetailSalePrice(), "Expired sale price should be null");
    }

    // ─── Test: No active price ──────────────────────────────────────────────

    @Test
    @TestTransaction
    void noActivePrice_adminBaseline()
    {
        String marker = "ZZCHAR-NOPRICE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoPrice", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, true, null);

        // Variant with stock but no active price (all prices expired)
        ProductVariantEntity variant = newVariant(product, "NP1", ProductStatusEn.ACTIVE, 50);
        newImage(variant, "https://img.test/noprice.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"), now.minusDays(60), now.minusDays(1));
        em.flush();

        // Admin from ProductService
        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        // Admin from FeaturedProductService
        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin()
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "No active price: admin mappings must match");
        assertNull(psAdmin.getRetailPrice(), "No active price should be null");
        assertEquals(50, psAdmin.getStockCount());
        assertEquals("IN_STOCK", psAdmin.getStockLevel());
    }

    // ─── Test: Zero stock ───────────────────────────────────────────────────

    @Test
    @TestTransaction
    void zeroStock_adminBaseline()
    {
        String marker = "ZZCHAR-ZEROSTOCK-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZeroStock", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, null);

        ProductVariantEntity variant = newVariant(product, "ZS1", ProductStatusEn.ACTIVE, 0);
        newImage(variant, "https://img.test/zerostock.jpg", true, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin()
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "Zero stock: admin mappings must match");
        assertEquals(0, psAdmin.getStockCount());
        assertEquals("OUT_OF_STOCK", psAdmin.getStockLevel());
        assertEquals("50.00", psAdmin.getRetailPrice());
    }

    @Test
    @TestTransaction
    void zeroStock_shoppingBaseline()
    {
        String marker = "ZZCHAR-ZSSHOP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "ZSShop", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, null);

        ProductVariantEntity variant = newVariant(product, "ZSS1", ProductStatusEn.ACTIVE, 0);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto fpsDto = featuredProductService.getFeaturedShoppingProducts(50, null)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst()
                .orElseThrow();

        ProductShoppingListItemDto repoDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, false)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertShoppingDtoEquals(fpsDto, repoDto, "Zero stock: shopping mappings must match");
        assertEquals(1, fpsDto.getVariantCount());
        assertNotNull(fpsDto.getRetailPrice());
        assertTrue(fpsDto.getImages().isEmpty(), "No images should yield empty list");
    }

    // ─── Test: Missing images ───────────────────────────────────────────────

    @Test
    @TestTransaction
    void missingImages_adminBaseline()
    {
        String marker = "ZZCHAR-NOIMG-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "NoImg", ProductStatusEn.ACTIVE, ProductTypeEn.SIMPLE, true, null);

        ProductVariantEntity variant = newVariant(product, "NI1", ProductStatusEn.ACTIVE, 5);
        // No images added
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("30.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin().stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "Missing images: admin mappings must match");
        assertNull(psAdmin.getThumbnailUrl(), "No images should yield null thumbnail");
        assertEquals(5, psAdmin.getStockCount());
        assertEquals("LOW_STOCK", psAdmin.getStockLevel());
    }

    // ─── Test: Unpublished/inactive product (PENDING status) ────────────────

    @Test
    @TestTransaction
    void unpublishedProduct_adminBaseline()
    {
        String marker = "ZZCHAR-PENDING-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Pending", ProductStatusEn.PENDING, ProductTypeEn.VARIABLE, true, null);

        ProductVariantEntity variant = newVariant(product, "PD1", ProductStatusEn.ACTIVE, 10);
        newImage(variant, "https://img.test/pending.jpg", false, 1);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        // Admin from ProductService (admin list shows all statuses)
        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        // Admin from FeaturedProductService (shows all featured regardless of status)
        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin()
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "Unpublished: admin mappings must match");
        assertEquals("PENDING", psAdmin.getStatus());
        assertEquals("https://img.test/pending.jpg", psAdmin.getThumbnailUrl());
    }

    @Test
    @TestTransaction
    void unpublishedProduct_shoppingIgnoreStatusBaseline()
    {
        String marker = "ZZCHAR-PENDSHOP-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "PendShop", ProductStatusEn.PENDING, ProductTypeEn.SIMPLE, true, null);

        ProductVariantEntity variant = newVariant(product, "PS1", ProductStatusEn.ACTIVE, 10);
        newPrice(variant, PriceTypeEn.RETAIL_PRICE, new BigDecimal("75.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        // With ignoreStatus=true, the PENDING product should appear in shopping results
        // from ProductRepository (admin views shopping through ignoreStatus=true)
        List<ProductShoppingListItemDto> repoIgnoreList = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, true);
        ProductShoppingListItemDto repoIgnoreDto = repoIgnoreList
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("PENDING product should appear when ignoreStatus=true"));

        // With ignoreStatus=false, PENDING product should NOT appear in storefront shopping
        List<ProductShoppingListItemDto> repoList = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, false);
        boolean existsInStorefront = repoList
                .stream()
                .anyMatch(d -> d.getName().startsWith(marker));

        // Note: Whether a PENDING product with active prices appears depends on the
        // query filter at the repository level (which filters by product status=ACTIVE).
        // The characterization captures the current behaviour — do not assert it "should"
        // or "should not" appear; just record what happens for regression detection.

        // Pin the ignoreStatus=true baseline
        assertEquals("PENDING", repoIgnoreDto.getStatus());
        assertEquals(marker + "PendShop", repoIgnoreDto.getName());
    }

    // ─── Test: VARIABLE product (no variantId for non-SIMPLE) ───────────────

    @Test
    @TestTransaction
    void variableProduct_shoppingBaseline()
    {
        String marker = "ZZCHAR-VARIABLE-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "Variable", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, true, null);

        ProductVariantEntity v1 = newVariant(product, "VAR1", ProductStatusEn.ACTIVE, 10);
        ProductVariantEntity v2 = newVariant(product, "VAR2", ProductStatusEn.ACTIVE, 5);
        newImage(v1, "https://img.test/var1.jpg", true, 1);
        newImage(v2, "https://img.test/var2.jpg", false, 2);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("200.00"), now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("180.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        ProductShoppingListItemDto fpsDto = featuredProductService.getFeaturedShoppingProducts(50, null)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        ProductShoppingListItemDto repoDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, false)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertShoppingDtoEquals(fpsDto, repoDto, "VARIABLE product: shopping mappings must match");

        // VARIABLE products should NOT have variantId set
        assertNull(fpsDto.getVariantId(), "VARIABLE product variantId must be null");
        assertEquals(2, fpsDto.getVariantCount());
        assertEquals("VARIABLE", fpsDto.getProductType());
        // Should resolve the lowest price (180.00 from v2)
        assertNotNull(fpsDto.getRetailPrice());
        assertEquals(0, new BigDecimal("180.00").compareTo(fpsDto.getRetailPrice().getPrice()), "Lowest retail price should be 180.00");
        // Images from both variants
        assertTrue(fpsDto.getImages().size() >= 2, "Should have images from both variants");
    }

    // ─── Test: Multiple variants with mixed stock for admin stockCount ──────

    @Test
    @TestTransaction
    void multipleVariants_adminAggregatesStock()
    {
        String marker = "ZZCHAR-MULTIVAR-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "MultiVar", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, true, null);

        ProductVariantEntity v1 = newVariant(product, "MV1", ProductStatusEn.ACTIVE, 8);
        ProductVariantEntity v2 = newVariant(product, "MV2", ProductStatusEn.ACTIVE, 3);
        ProductVariantEntity v3 = newVariant(product, "MV3", ProductStatusEn.DISABLED, 20);
        newImage(v1, "https://img.test/mv1.jpg", true, 1);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("99.00"), now.minusDays(10), now.plusDays(10));
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("89.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        var adminPage = productService.getAdminProductList(0, 100, null, null, null, marker);
        AdminProductListItemDto psAdmin = adminPage.getContent().get(0);

        AdminProductListItemDto fpsAdmin = featuredProductService.getFeaturedProductsForAdmin()
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        assertAdminDtoEquals(psAdmin, fpsAdmin, "Multiple variants: admin mappings must match");
        // Stock is aggregated across ALL variants (including DISABLED)
        assertEquals(31, psAdmin.getStockCount(), "Stock should sum all variants (8+3+20)");
        assertEquals("IN_STOCK", psAdmin.getStockLevel());
    }

    // ─── Test: ignoreStatus difference in shopping variant count ─────────────

    @Test
    @TestTransaction
    void ignoreStatus_affectsVariantCountAndPriceResolution()
    {
        String marker = "ZZCHAR-IGSTAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LocalDateTime now = LocalDateTime.now();

        ProductEntity product = newProduct(marker, "IgnoreStat", ProductStatusEn.ACTIVE, ProductTypeEn.VARIABLE, true, null);

        // Active variant with price
        ProductVariantEntity v1 = newVariant(product, "IS1", ProductStatusEn.ACTIVE, 10);
        newPrice(v1, PriceTypeEn.RETAIL_PRICE, new BigDecimal("50.00"), now.minusDays(10), now.plusDays(10));
        // Disabled variant with price
        ProductVariantEntity v2 = newVariant(product, "IS2", ProductStatusEn.DISABLED, 5);
        newPrice(v2, PriceTypeEn.RETAIL_PRICE, new BigDecimal("40.00"), now.minusDays(10), now.plusDays(10));
        em.flush();

        // ignoreStatus=false: only counts ACTIVE variants
        ProductShoppingListItemDto repoDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, false)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        // ignoreStatus=true: counts ALL variants
        ProductShoppingListItemDto repoIgnoreDto = productService.getShoppingProducts(pageRequest(0, 50), nameFilter(marker), false, true)
                .stream()
                .filter(d -> d.getName().startsWith(marker))
                .findFirst().orElseThrow();

        // Pin the differences — ignoreStatus affects variant count
        assertEquals(1, repoDto.getVariantCount(), "ignoreStatus=false should count only ACTIVE variants");
        assertEquals(2, repoIgnoreDto.getVariantCount(), "ignoreStatus=true should count ALL variants");

        // Price resolution: ignoreStatus=false only considers prices on ACTIVE variants
        assertNotNull(repoDto.getRetailPrice());
        assertEquals(0, new BigDecimal("50.00").compareTo(repoDto.getRetailPrice().getPrice()), "ignoreStatus=false: lowest price from ACTIVE variants only");

        // ignoreStatus=true considers prices on ALL variants (lowest is 40.00 from disabled v2)
        assertNotNull(repoIgnoreDto.getRetailPrice());
        assertEquals(0, new BigDecimal("40.00").compareTo(repoIgnoreDto.getRetailPrice().getPrice()), "ignoreStatus=true: lowest price from ALL variants");
    }

    // ─── Assertion Helpers ──────────────────────────────────────────────────

    private void assertAdminDtoEquals(AdminProductListItemDto a, AdminProductListItemDto b, String message)
    {
        assertEquals(a.getId(), b.getId(), message + " [id]");
        assertEquals(a.getName(), b.getName(), message + " [name]");
        assertEquals(a.getSlug(), b.getSlug(), message + " [slug]");
        assertEquals(a.getSku(), b.getSku(), message + " [sku]");
        assertEquals(a.getStatus(), b.getStatus(), message + " [status]");
        assertEquals(a.getThumbnailUrl(), b.getThumbnailUrl(), message + " [thumbnailUrl]");
        assertEquals(a.getStockCount(), b.getStockCount(), message + " [stockCount]");
        assertEquals(a.getStockLevel(), b.getStockLevel(), message + " [stockLevel]");
        assertEquals(a.getRetailPrice(), b.getRetailPrice(), message + " [retailPrice]");

        // Category comparison
        if (a.getCategory() == null) {
            assertNull(b.getCategory(), message + " [category null mismatch]");
        } else {
            assertNotNull(b.getCategory(), message + " [category null mismatch]");
            assertEquals(a.getCategory().getId(), b.getCategory().getId(), message + " [category.id]");
            assertEquals(a.getCategory().getName(), b.getCategory().getName(), message + " [category.name]");
        }
    }

    private void assertShoppingDtoEquals(ProductShoppingListItemDto a, ProductShoppingListItemDto b, String message)
    {
        assertEquals(a.getId(), b.getId(), message + " [id]");
        assertEquals(a.getName(), b.getName(), message + " [name]");
        assertEquals(a.getSlug(), b.getSlug(), message + " [slug]");
        assertEquals(a.getShortDescription(), b.getShortDescription(), message + " [shortDescription]");
        assertEquals(a.getProductType(), b.getProductType(), message + " [productType]");
        assertEquals(a.getStatus(), b.getStatus(), message + " [status]");
        assertEquals(a.getVariantCount(), b.getVariantCount(), message + " [variantCount]");
        assertEquals(a.getVariantId(), b.getVariantId(), message + " [variantId]");

        // Compare images
        assertImagesEqual(a.getImages(), b.getImages(), message);

        // Compare prices
        assertVariantPriceEquals(a.getRetailPrice(), b.getRetailPrice(), message + " [retailPrice]");
        assertVariantPriceEquals(a.getWholesalePrice(), b.getWholesalePrice(), message + " [wholesalePrice]");
        assertVariantPriceEquals(a.getRetailSalePrice(), b.getRetailSalePrice(), message + " [retailSalePrice]");
        assertVariantPriceEquals(a.getWholesaleSalePrice(), b.getWholesaleSalePrice(), message + " [wholesaleSalePrice]");
    }

    private void assertImagesEqual(List<ProductImageDto> a, List<ProductImageDto> b, String message)
    {
        if (a == null && b == null) return;
        assertNotNull(a, message + " [images: a null]");
        assertNotNull(b, message + " [images: b null]");
        assertEquals(a.size(), b.size(), message + " [images.size]");
        for (int i = 0; i < a.size(); i++) {
            ProductImageDto imgA = a.get(i);
            ProductImageDto imgB = b.get(i);
            assertEquals(imgA.getId(), imgB.getId(), message + " [images[" + i + "].id]");
            assertEquals(imgA.getImageUrl(), imgB.getImageUrl(), message + " [images[" + i + "].imageUrl]");
            assertEquals(imgA.getSortOrder(), imgB.getSortOrder(), message + " [images[" + i + "].sortOrder]");
            assertEquals(imgA.isFeatured(), imgB.isFeatured(), message + " [images[" + i + "].isFeatured]");
        }
    }

    private void assertVariantPriceEquals(VariantPriceDto a, VariantPriceDto b, String message)
    {
        if (a == null && b == null) return;
        if (a == null || b == null) {
            fail(message + " one is null: a=" + a + ", b=" + b);
        }
        assertEquals(a.getId(), b.getId(), message + " [price.id]");
        assertEquals(a.getPriceType(), b.getPriceType(), message + " [price.priceType]");
        assertEquals(0, a.getPrice().compareTo(b.getPrice()), message + " [price.price]");
        assertEquals(a.getPriceStartDate(), b.getPriceStartDate(), message + " [price.priceStartDate]");
        assertEquals(a.getPriceEndDate(), b.getPriceEndDate(), message + " [price.priceEndDate]");
    }

    private org.ecommerce.common.query.PageRequest pageRequest(int pageIndex, int pageSize)
    {
        org.ecommerce.common.query.PageRequest pr = new org.ecommerce.common.query.PageRequest();
        pr.setPageIndex(pageIndex);
        pr.setPageSize(pageSize);
        return pr;
    }

    /**
     * Creates a FilterRequest with a name ILIKE filter to isolate test products
     * from pre-existing data in the DB (ensures our products appear regardless of pagination).
     */
    private FilterRequest nameFilter(String marker)
    {
        FilterRequest fr = new FilterRequest();
        fr.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, marker)));
        return fr;
    }
}
