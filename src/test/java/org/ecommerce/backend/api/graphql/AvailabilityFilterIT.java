package org.ecommerce.backend.api.graphql;

// Feature: catalogue-browsing-experience
// Task 1.4: Integration tests for the availability filter

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.CatalogueSortEn;
import org.ecommerce.common.enums.PriceBasisEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration tests for the availability filter ({@code inStockOnly}).
 * <p>
 * Follows shared-database discipline (KNOWN-LIMITATIONS §5):
 * - Uses {@link TestTransaction} (rolled back afterward) so no manual cleanup is needed
 * - Uses an ILIKE filter on a unique marker to isolate tracked products from pre-existing data
 * - Asserts RELATIVE presence/absence of tracked product IDs, never absolute counts on the shared DB
 * <p>
 * Seeds two products with unique searchable names:
 * - Product A: ACTIVE, has a variant with stockQuantity > 0 (in-stock) and an ACTIVE price
 * - Product B: ACTIVE, has a variant with stockQuantity = 0 (out-of-stock) and an ACTIVE price
 * <p>
 * Test cases:
 * - inStockOnly=true with search term: Product A present, Product B absent
 * - inStockOnly omitted/false with search term: both products present
 * - Combined with a category filter: same in-stock filtering applies within the category
 * - totalElements consistent with the page content under the filter (Req 3.4)
 */
@QuarkusTest
class AvailabilityFilterIT
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

    private ProductVariantEntity newVariant(ProductEntity product, int stockQuantity)
    {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        v.setSku("SKU-" + UUID.randomUUID());
        v.setStatus(ProductStatusEn.ACTIVE);
        v.setStockQuantity(stockQuantity);
        v.persist();
        return v;
    }

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

    private PageRequest pageOf(int index, int size)
    {
        PageRequest pr = new PageRequest();
        pr.setPageIndex(index);
        pr.setPageSize(size);
        return pr;
    }

    /**
     * Creates a FilterRequest with an ILIKE filter on the product name matching our marker.
     * This isolates our tracked products from the rest of the shared database.
     */
    private FilterRequest filterByMarker(String marker)
    {
        FilterRequest fr = new FilterRequest();
        fr.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, marker)));
        return fr;
    }

    /**
     * Creates a FilterRequest with ILIKE on marker AND a category.id IN filter.
     */
    private FilterRequest filterByMarkerAndCategory(String marker, UUID categoryId)
    {
        FilterRequest fr = new FilterRequest();
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("name", FilterOperator.ILIKE, marker));
        filters.add(new Filter("category.id", FilterOperator.IN, List.of(categoryId.toString())));
        fr.setFilters(filters);
        return fr;
    }

    private boolean containsProduct(List<ProductShoppingListItemDto> results, UUID productId)
    {
        String id = productId.toString();
        return results.stream().anyMatch(dto -> id.equals(dto.getId()));
    }

    // ─── inStockOnly=true: in-stock present, out-of-stock absent ────────────

    @Test
    @TestTransaction
    void inStockOnly_true_returnsOnlyInStockProducts()
    {
        String marker = "ZZAVAIL-INSTOCK-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product A: in-stock (stockQuantity > 0)
        ProductEntity inStockProduct = newProduct(marker, "InStock");
        ProductVariantEntity vInStock = newVariant(inStockProduct, 10);
        addActivePrice(vInStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));

        // Product B: out-of-stock (stockQuantity = 0)
        ProductEntity outOfStockProduct = newProduct(marker, "OutOfStock");
        ProductVariantEntity vOutOfStock = newVariant(outOfStockProduct, 0);
        addActivePrice(vOutOfStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, true);

        assertTrue(containsProduct(results, inStockProduct.getId()),
                "In-stock product must appear when inStockOnly=true");
        assertFalse(containsProduct(results, outOfStockProduct.getId()),
                "Out-of-stock product (stockQuantity=0) must NOT appear when inStockOnly=true");
    }

    // ─── inStockOnly omitted (null): both products present ──────────────────

    @Test
    @TestTransaction
    void inStockOnly_null_returnsBothInStockAndOutOfStockProducts()
    {
        String marker = "ZZAVAIL-BOTH-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product A: in-stock
        ProductEntity inStockProduct = newProduct(marker, "InStock");
        ProductVariantEntity vInStock = newVariant(inStockProduct, 10);
        addActivePrice(vInStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));

        // Product B: out-of-stock
        ProductEntity outOfStockProduct = newProduct(marker, "OutOfStock");
        ProductVariantEntity vOutOfStock = newVariant(outOfStockProduct, 0);
        addActivePrice(vOutOfStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);

        assertTrue(containsProduct(results, inStockProduct.getId()),
                "In-stock product must appear when inStockOnly is omitted (null)");
        assertTrue(containsProduct(results, outOfStockProduct.getId()),
                "Out-of-stock product must appear when inStockOnly is omitted (null) — " +
                        "the filter must not alter behaviour when absent (Req 3.5)");
    }

    // ─── Combined with category filter ──────────────────────────────────────

    @Test
    @TestTransaction
    void inStockOnly_combinedWithCategory_filtersWithinCategory()
    {
        String marker = "ZZAVAIL-CAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Create a category
        CategoryEntity category = new CategoryEntity();
        category.setName(marker + "TestCategory");
        category.setSlug((marker + "test-category-" + UUID.randomUUID()).toLowerCase());
        category.persist();

        // Product A: in-stock, in the category
        ProductEntity inStockProduct = newProduct(marker, "CatInStock");
        inStockProduct.setCategory(category);
        ProductVariantEntity vInStock = newVariant(inStockProduct, 5);
        addActivePrice(vInStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(40));

        // Product B: out-of-stock, in the same category
        ProductEntity outOfStockProduct = newProduct(marker, "CatOutOfStock");
        outOfStockProduct.setCategory(category);
        ProductVariantEntity vOutOfStock = newVariant(outOfStockProduct, 0);
        addActivePrice(vOutOfStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(20));

        em.flush();

        // inStockOnly=true combined with category filter
        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarkerAndCategory(marker, category.getId()),
                false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, true);

        assertTrue(containsProduct(results, inStockProduct.getId()),
                "In-stock product in the category must appear when inStockOnly=true + category filter");
        assertFalse(containsProduct(results, outOfStockProduct.getId()),
                "Out-of-stock product in the category must NOT appear when inStockOnly=true + category filter — " +
                        "the availability predicate must compose with category (Req 3.4)");
    }

    // ─── totalElements consistent with content under the filter (Req 3.4) ───

    @Test
    @TestTransaction
    void totalElements_consistentWithPageContent_underAvailabilityFilter()
    {
        String marker = "ZZAVAIL-COUNT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product A: in-stock
        ProductEntity inStockProduct = newProduct(marker, "CountInStock");
        ProductVariantEntity vInStock = newVariant(inStockProduct, 15);
        addActivePrice(vInStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        // Product B: out-of-stock
        ProductEntity outOfStockProduct = newProduct(marker, "CountOutOfStock");
        ProductVariantEntity vOutOfStock = newVariant(outOfStockProduct, 0);
        addActivePrice(vOutOfStock, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(25));

        em.flush();

        FilterRequest filter = filterByMarker(marker);

        // With inStockOnly=true: count must match the number of items in the page
        List<ProductShoppingListItemDto> filteredResults = productService.getShoppingProducts(
                pageOf(0, 50), filter, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, true);
        long filteredCount = productService.countShoppingProducts(filter, false, true);

        assertEquals(filteredResults.size(), filteredCount,
                "totalElements (count query with inStockOnly=true) must equal the number of items " +
                        "returned on the page — a divergence is the pagination lie Req 3.4 forbids. " +
                        "Page content: " + filteredResults.size() + ", count: " + filteredCount);

        // Verify the filtered result contains only the in-stock product
        assertTrue(containsProduct(filteredResults, inStockProduct.getId()),
                "In-stock product must appear in filtered results");
        assertFalse(containsProduct(filteredResults, outOfStockProduct.getId()),
                "Out-of-stock product must NOT appear in filtered results");
        assertEquals(1, filteredCount,
                "Only the in-stock product should be counted when inStockOnly=true");

        // Without inStockOnly: count must include both
        List<ProductShoppingListItemDto> unfilteredResults = productService.getShoppingProducts(
                pageOf(0, 50), filter, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);
        long unfilteredCount = productService.countShoppingProducts(filter, false, null);

        assertEquals(unfilteredResults.size(), unfilteredCount,
                "totalElements without inStockOnly must equal page content size — " +
                        "count: " + unfilteredCount + ", page: " + unfilteredResults.size());
        assertEquals(2, unfilteredCount,
                "Both tracked products must be counted when inStockOnly is omitted");
    }
}
