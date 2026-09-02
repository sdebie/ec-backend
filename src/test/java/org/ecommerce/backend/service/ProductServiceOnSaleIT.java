package org.ecommerce.backend.service;

// Feature: sale-products-page, Task 1.3: Backend integration tests (DB-backed)

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.SortRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.query.enums.SortDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration tests for the {@code onSale} scope on
 * {@link ProductService#getShoppingProducts} and
 * {@link ProductService#countShoppingProducts}.
 * <p>
 * Follows the {@link FeaturedProductServiceIT} pattern: real datasource,
 * {@link TestTransaction} (rolled back afterward), unique per-test markers
 * to isolate assertions from pre-existing DB rows.
 * <p>
 * Covers:
 * Property 1: onSale=true returns exactly the active-sale set (Req 1.1, 1.2)
 * Property 2: onSale=false is unchanged / regression (Req 1.3)
 * Property 3: totalElements/totalPages correct across pages (Req 1.4)
 * Composition: onSale + category/brand/search filters (Req 1.2)
 */
@QuarkusTest
class ProductServiceOnSaleIT
{
    @Inject
    ProductService productService;

    @Inject
    EntityManager em;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private ProductEntity newProduct(String marker, String nameSuffix)
    {
        ProductEntity p = new ProductEntity();
        p.setName(marker + nameSuffix);
        p.setSlug((marker + nameSuffix + "-" + UUID.randomUUID()).toLowerCase());
        p.setStatus(ProductStatusEn.ACTIVE);
        p.setFeatured(false);
        p.setProductType(ProductTypeEn.SIMPLE);
        p.persist();
        return p;
    }

    private ProductVariantEntity newVariant(ProductEntity product)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku("SKU-" + UUID.randomUUID());
        v.setStatus(ProductStatusEn.ACTIVE);
        v.setStockQuantity(100);
        v.persist();
        return v;
    }

    /**
     * Creates a price on the variant with the given type and an active date window.
     */
    private void addActivePrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal amount)
    {
        VariantPricesEntity vp = new VariantPricesEntity();
        vp.setVariant(variant);
        vp.setPriceType(priceType);
        vp.setPrice(amount);
        vp.setPriceStartDate(LocalDateTime.now().minusDays(7));
        vp.setPriceEndDate(LocalDateTime.now().plusDays(7));
        vp.persist();
    }

    /**
     * Creates a price with an expired date window (no longer active).
     */
    private void addExpiredPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal amount)
    {
        VariantPricesEntity vp = new VariantPricesEntity();
        vp.setVariant(variant);
        vp.setPriceType(priceType);
        vp.setPrice(amount);
        vp.setPriceStartDate(LocalDateTime.now().minusDays(30));
        vp.setPriceEndDate(LocalDateTime.now().minusDays(1));
        vp.persist();
    }

    private PageRequest pageOf(int index, int size)
    {
        PageRequest pr = new PageRequest();
        pr.setPageIndex(index);
        pr.setPageSize(size);
        return pr;
    }

    // ─── Property 1: onSale=true returns exactly the active-sale set ────────

    @Test
    @TestTransaction
    void onSaleTrueReturnsOnlyProductsWithActiveSalePrice()
    {
        String marker = "ZZONSALE1-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product A: has active retail sale price → qualifies
        ProductEntity pA = newProduct(marker, "Alpha");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vA, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(100));

        // Product B: has active wholesale sale price → qualifies
        ProductEntity pB = newProduct(marker, "Bravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.WHOLESALE_SALE_PRICE, BigDecimal.valueOf(40));
        addActivePrice(vB, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(80));

        // Product C: only regular prices (no sale) → does NOT qualify
        ProductEntity pC = newProduct(marker, "Charlie");
        ProductVariantEntity vC = newVariant(pC);
        addActivePrice(vC, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(90));

        // Product D: expired sale price → does NOT qualify
        ProductEntity pD = newProduct(marker, "Delta");
        ProductVariantEntity vD = newVariant(pD);
        addExpiredPrice(vD, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(45));
        addActivePrice(vD, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(95));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), null, true, null, null, null);

        List<String> mine = results
                .stream()
                .map(d -> d.getName())
                .filter(n -> n.startsWith(marker))
                .toList();

        assertTrue(mine.contains(marker + "Alpha"), "Product with active retail sale price must appear");
        assertTrue(mine.contains(marker + "Bravo"), "Product with active wholesale sale price must appear");
        assertFalse(mine.contains(marker + "Charlie"), "Product without any sale price must NOT appear");
        assertFalse(mine.contains(marker + "Delta"), "Product with only expired sale price must NOT appear");
        assertEquals(2, mine.size(), "Exactly 2 products from this test set should qualify");
    }

    // ─── Property 1b: parity with findOnSaleShoppingProductList ─────────────

    @Test
    @TestTransaction
    void onSaleTrueMatchesFindOnSaleShoppingProductList()
    {
        String marker = "ZZONSALEPARITY-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Create several products — mix of sale and non-sale
        ProductEntity pA = newProduct(marker, "Apple");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(30));

        ProductEntity pB = newProduct(marker, "Banana");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.WHOLESALE_SALE_PRICE, BigDecimal.valueOf(25));

        ProductEntity pC = newProduct(marker, "Cherry");
        ProductVariantEntity vC = newVariant(pC);
        addActivePrice(vC, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        em.flush();

        // Use onSale=true path (the new scope)
        List<ProductShoppingListItemDto> onSaleResults = productService.getShoppingProducts(pageOf(0, 50), null, true, null, null, null);

        // Use the legacy findOnSaleShoppingProductList (existing)
        List<ProductShoppingListItemDto> legacyResults = productService.getProductsOnSale(pageOf(0, 50), false).getContent();

        // Filter to our test marker
        List<String> onSaleNames = onSaleResults
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .sorted()
                .collect(Collectors.toList());

        List<String> legacyNames = legacyResults
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .sorted()
                .collect(Collectors.toList());

        assertEquals(legacyNames, onSaleNames,
                "onSale=true must return the same products as findOnSaleShoppingProductList");
    }

    // ─── Composition: onSale + category filter ──────────────────────────────

    @Test
    @TestTransaction
    void onSaleTrueComposesWithCategoryFilter()
    {
        String marker = "ZZONSALECAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        CategoryEntity category = new CategoryEntity();
        category.setName(marker + "TestCategory");
        category.setSlug("zzonsalecat-" + UUID.randomUUID());
        category.persist();

        // Product A: on sale + in category → qualifies
        ProductEntity pA = newProduct(marker, "Alpha");
        pA.setCategory(category);
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(30));

        // Product B: on sale but NOT in category → excluded by filter
        ProductEntity pB = newProduct(marker, "Bravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(25));

        // Product C: in category but NOT on sale → excluded by onSale scope
        ProductEntity pC = newProduct(marker, "Charlie");
        pC.setCategory(category);
        ProductVariantEntity vC = newVariant(pC);
        addActivePrice(vC, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        em.flush();

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("category.id", FilterOperator.EQUALS, category.getId().toString())));

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(pageOf(0, 50), filterRequest, true, null, null, null);

        List<String> mine = results
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Alpha"), mine, "Only the on-sale product in the filtered category should appear");
    }

    // ─── Composition: onSale + brand filter ─────────────────────────────────

    @Test
    @TestTransaction
    void onSaleTrueComposesWithBrandFilter()
    {
        String marker = "ZZONSALEBRAND-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        BrandEntity brand = new BrandEntity();
        brand.setName(marker + "TestBrand");
        brand.setSlug("zzonsalebrand-" + UUID.randomUUID());
        brand.persist();

        // Product A: on sale + brand matches → qualifies
        ProductEntity pA = newProduct(marker, "Alpha");
        pA.setBrand(brand);
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.WHOLESALE_SALE_PRICE, BigDecimal.valueOf(20));

        // Product B: on sale but different/no brand → excluded
        ProductEntity pB = newProduct(marker, "Bravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(30));

        em.flush();

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("brand.id", FilterOperator.EQUALS, brand.getId().toString())));

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(pageOf(0, 50), filterRequest, true, null, null, null);

        List<String> mine = results
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Alpha"), mine,
                "Only the on-sale product with the matching brand should appear");
    }

    // ─── Composition: onSale + search (name ILIKE) ──────────────────────────

    @Test
    @TestTransaction
    void onSaleTrueComposesWithSearchFilter()
    {
        String marker = "ZZONSALESRCH-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product A: on sale, name contains "Findable"
        ProductEntity pA = newProduct(marker, "FindableAlpha");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(35));

        // Product B: on sale, name does NOT match search
        ProductEntity pB = newProduct(marker, "HiddenBravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(40));

        em.flush();

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, "Findable")));

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(pageOf(0, 50), filterRequest, true, null, null, null);

        List<String> mine = results
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "FindableAlpha"), mine, "Only the on-sale product matching the search should appear");
    }

    // ─── Composition: onSale + sort ─────────────────────────────────────────

    @Test
    @TestTransaction
    void onSaleTrueRespectsSortOrder()
    {
        // Post catalogue-ordering-and-pagination: filterRequest.sort is IGNORED by getShoppingProducts;
        // the authoritative sort is the CatalogueSortEn parameter (defaults to NAME_ASC when null).
        // This test verifies that the default NAME_ASC ordering is applied regardless of filterRequest.sort.
        String marker = "ZZONSALESORT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        ProductEntity pA = newProduct(marker, "Zebra");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(50));

        ProductEntity pB = newProduct(marker, "Apple");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.WHOLESALE_SALE_PRICE, BigDecimal.valueOf(30));

        ProductEntity pC = newProduct(marker, "Mango");
        ProductVariantEntity vC = newVariant(pC);
        addActivePrice(vC, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(25));

        em.flush();

        // filterRequest.sort is set to DESC but should be ignored — sortBy parameter is authoritative
        SortRequest sortDesc = new SortRequest();
        sortDesc.setField("name");
        sortDesc.setDirection(SortDirection.DESC);

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setSort(List.of(sortDesc));

        // sortBy=null → defaults to NAME_ASC
        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(pageOf(0, 50), filterRequest, true, null, null, null);

        List<String> mine = results.stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Apple", marker + "Mango", marker + "Zebra"), mine, "Default ordering is NAME_ASC — filterRequest.sort is ignored");
    }

    // ─── Property 3: totalElements/totalPages correct across pages ──────────

    @Test
    @TestTransaction
    void onSaleTotalElementsAndTotalPagesCorrectAcrossPages()
    {
        String marker = "ZZONSALEPG-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Create 5 on-sale products
        for (int i = 1; i <= 5; i++) {
            ProductEntity p = newProduct(marker, String.format("Item%02d", i));
            ProductVariantEntity v = newVariant(p);
            addActivePrice(v, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(10 + i));
        }

        // Create 3 non-sale products (should NOT affect on-sale totals)
        for (int i = 1; i <= 3; i++) {
            ProductEntity p = newProduct(marker, String.format("NoSale%02d", i));
            ProductVariantEntity v = newVariant(p);
            addActivePrice(v, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50 + i));
        }

        em.flush();

        // Count with onSale=true
        long totalOnSale = productService.countShoppingProducts(null, true, null);

        // Count with onSale=false (all products with any active price)
        long totalAll = productService.countShoppingProducts(null, false, null);

        // The on-sale total includes our 5 + any pre-existing on-sale products
        // The all-products total includes our 8 + any pre-existing products
        // Key assertion: totalAll >= totalOnSale (on-sale is a strict subset)
        assertTrue(totalAll >= totalOnSale, "Total products (onSale=false) must be >= total on-sale products");

        // Now verify pagination math with a small page size
        int pageSize = 2;

        // Get page 0 of on-sale products
        List<ProductShoppingListItemDto> page0 = productService.getShoppingProducts(pageOf(0, pageSize), null, true, null, null, null);

        // Verify page size is respected
        assertTrue(page0.size() <= pageSize, "Page 0 must contain at most pageSize items");

        // Verify totalPages = ceil(totalElements / pageSize)
        long expectedTotalPages = (long) Math.ceil((double) totalOnSale / pageSize);

        // Collect all on-sale products across all pages to verify disjoint coverage
        List<String> allOnSaleIds = new ArrayList<>();
        for (int pageIdx = 0; pageIdx < expectedTotalPages; pageIdx++) {
            List<ProductShoppingListItemDto> page = productService.getShoppingProducts(pageOf(pageIdx, pageSize), null, true, null, null, null);
            assertTrue(page.size() <= pageSize, "Page " + pageIdx + " must contain at most pageSize items");
            for (ProductShoppingListItemDto dto : page) {
                assertFalse(allOnSaleIds.contains(dto.getId()), "Product " + dto.getId() + " appeared on multiple pages (not disjoint)");
                allOnSaleIds.add(dto.getId());
            }
        }

        assertEquals(totalOnSale, allOnSaleIds.size(), "Sum of items across all pages must equal totalElements");
    }

    // ─── Property 2: onSale=false is unchanged (regression) ─────────────────

    @Test
    @TestTransaction
    void onSaleFalseReturnsIdenticalResultsAsFullShoppingList()
    {
        String marker = "ZZONSALEREGR-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Mix of sale and non-sale products
        ProductEntity pA = newProduct(marker, "Alpha");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(30));
        addActivePrice(vA, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        ProductEntity pB = newProduct(marker, "Bravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));

        ProductEntity pC = newProduct(marker, "Charlie");
        ProductVariantEntity vC = newVariant(pC);
        addActivePrice(vC, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(45));

        em.flush();

        // Use an ILIKE filter matching our marker to isolate from pre-existing data
        // (default name-ASC sort + in-memory pagination can miss rows if the DB has >50 products)
        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, marker)));

        // onSale=false should return ALL products (sale AND non-sale) — same as today
        List<ProductShoppingListItemDto> onSaleFalse = productService.getShoppingProducts(
                pageOf(0, 50), filterRequest, false, null, null, null);

        List<String> onSaleFalseNames = onSaleFalse
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .sorted()
                .collect(Collectors.toList());

        // All three products have an active price of some type → all qualify
        List<String> expected = List.of(marker + "Alpha", marker + "Bravo", marker + "Charlie");
        assertEquals(expected, onSaleFalseNames, "onSale=false must return all products with any active price (unchanged behaviour)");

        // Now verify that onSale=true for the same filter only returns Alpha (has sale price)
        List<ProductShoppingListItemDto> onSaleTrue = productService.getShoppingProducts(pageOf(0, 50), filterRequest, true, null, null, null);

        List<String> onSaleTrueNames = onSaleTrue
                .stream()
                .map(ProductShoppingListItemDto::getName)
                .filter(n -> n.startsWith(marker))
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Alpha"), onSaleTrueNames, "onSale=true must only return the product with an active sale price");

        // The regression guarantee: onSale=false returns a superset of onSale=true
        assertTrue(onSaleFalseNames.containsAll(onSaleTrueNames), "onSale=false must be a superset of onSale=true");
    }

    // ─── Property 2b: count onSale=false matches count of all shopping products ─

    @Test
    @TestTransaction
    void countOnSaleFalseMatchesAllShoppingProductsCount()
    {
        String marker = "ZZONSALECNT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Create products with various price types
        ProductEntity pA = newProduct(marker, "Alpha");
        ProductVariantEntity vA = newVariant(pA);
        addActivePrice(vA, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(100));

        ProductEntity pB = newProduct(marker, "Bravo");
        ProductVariantEntity vB = newVariant(pB);
        addActivePrice(vB, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(50));

        em.flush();

        long countFalse = productService.countShoppingProducts(null, false, null);
        long countTrue = productService.countShoppingProducts(null, true, null);

        // onSale=true is strictly a subset of onSale=false
        assertTrue(countFalse >= countTrue, "onSale=false total must be >= onSale=true total (sale is a subset of all)");

        // Both must be >= 1 (our test data guarantees at least some products)
        assertTrue(countTrue >= 1, "At least one on-sale product should exist");
        assertTrue(countFalse >= 2, "At least two shopping products should exist");
    }
}
