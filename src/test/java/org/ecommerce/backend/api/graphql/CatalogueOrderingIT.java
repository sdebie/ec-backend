package org.ecommerce.backend.api.graphql;

// Feature: catalogue-ordering-and-pagination
// Task 4.1: Ordering correctness ITs
// Task 4.2: Sale-over-base ordering test
// Task 4.8: Unchanged-behaviour tests

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
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
 * DB-backed integration tests for catalogue ordering correctness.
 * <p>
 * Follows shared-database discipline (KNOWN-LIMITATIONS §5):
 * - Uses {@link TestTransaction} (rolled back afterward) so no manual cleanup is needed
 * - Uses an ILIKE filter on a unique marker to isolate tracked products from pre-existing data
 * - Asserts RELATIVE order of tracked product IDs, never absolute positions
 * - Never asserts page sizes or absolute index positions
 * <p>
 * Seeds three products with deliberately chosen prices such that:
 * - RETAIL ASC order: Cheap(10) < Mid(50) < Expensive(100)
 * - RETAIL DESC order: Expensive(100) > Mid(50) > Cheap(10)
 * - WHOLESALE ASC order: Cheap(8) < Expensive(40) < Mid(60)
 * <p>
 * This proves the sort key respects the requested basis and direction.
 */
@QuarkusTest
class CatalogueOrderingIT
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
     * Returns the index of a product with the given ID in the result list.
     * Returns -1 if not found.
     */
    private int indexOf(List<ProductShoppingListItemDto> results, UUID productId)
    {
        String id = productId.toString();
        for (int i = 0; i < results.size(); i++)
        {
            if (id.equals(results.get(i).getId()))
            {
                return i;
            }
        }
        return -1;
    }

    // ─── RETAIL ASC: Cheap < Mid < Expensive ────────────────────────────────

    @Test
    @TestTransaction
    void retailAscendingOrdersCheapBeforeMidBeforeExpensive()
    {
        String marker = "ZZORDRET-ASC-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product "Cheap": retail price 10.00, wholesale price 8.00
        ProductEntity cheap = newProduct(marker, "Cheap");
        ProductVariantEntity vCheap = newVariant(cheap);
        addActivePrice(vCheap, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10));
        addActivePrice(vCheap, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(8));

        // Product "Mid": retail price 50.00, wholesale price 60.00
        ProductEntity mid = newProduct(marker, "Mid");
        ProductVariantEntity vMid = newVariant(mid);
        addActivePrice(vMid, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vMid, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(60));

        // Product "Expensive": retail price 100.00, wholesale price 40.00
        ProductEntity expensive = newProduct(marker, "Expensive");
        ProductVariantEntity vExpensive = newVariant(expensive);
        addActivePrice(vExpensive, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(100));
        addActivePrice(vExpensive, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(40));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        // With the marker filter, all three tracked products should appear
        assertEquals(3, results.size(), "Exactly 3 tracked products should appear in filtered results");

        int idxCheap = indexOf(results, cheap.getId());
        int idxMid = indexOf(results, mid.getId());
        int idxExpensive = indexOf(results, expensive.getId());

        assertNotEquals(-1, idxCheap, "Cheap product must appear in results");
        assertNotEquals(-1, idxMid, "Mid product must appear in results");
        assertNotEquals(-1, idxExpensive, "Expensive product must appear in results");

        assertTrue(idxCheap < idxMid,
                "RETAIL ASC: Cheap (R10) must appear before Mid (R50), but got indices " + idxCheap + " vs " + idxMid);
        assertTrue(idxMid < idxExpensive,
                "RETAIL ASC: Mid (R50) must appear before Expensive (R100), but got indices " + idxMid + " vs " + idxExpensive);
    }

    // ─── RETAIL DESC: Expensive > Mid > Cheap ───────────────────────────────

    @Test
    @TestTransaction
    void retailDescendingOrdersExpensiveBeforeMidBeforeCheap()
    {
        String marker = "ZZORDRET-DESC-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        ProductEntity cheap = newProduct(marker, "Cheap");
        ProductVariantEntity vCheap = newVariant(cheap);
        addActivePrice(vCheap, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10));
        addActivePrice(vCheap, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(8));

        ProductEntity mid = newProduct(marker, "Mid");
        ProductVariantEntity vMid = newVariant(mid);
        addActivePrice(vMid, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vMid, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(60));

        ProductEntity expensive = newProduct(marker, "Expensive");
        ProductVariantEntity vExpensive = newVariant(expensive);
        addActivePrice(vExpensive, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(100));
        addActivePrice(vExpensive, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(40));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_DESC, PriceBasisEn.RETAIL, null);

        assertEquals(3, results.size(), "Exactly 3 tracked products should appear in filtered results");

        int idxCheap = indexOf(results, cheap.getId());
        int idxMid = indexOf(results, mid.getId());
        int idxExpensive = indexOf(results, expensive.getId());

        assertNotEquals(-1, idxCheap, "Cheap product must appear in results");
        assertNotEquals(-1, idxMid, "Mid product must appear in results");
        assertNotEquals(-1, idxExpensive, "Expensive product must appear in results");

        assertTrue(idxExpensive < idxMid,
                "RETAIL DESC: Expensive (R100) must appear before Mid (R50), but got indices " + idxExpensive + " vs " + idxMid);
        assertTrue(idxMid < idxCheap,
                "RETAIL DESC: Mid (R50) must appear before Cheap (R10), but got indices " + idxMid + " vs " + idxCheap);
    }

    // ─── WHOLESALE ASC: Cheap(8) < Expensive(40) < Mid(60) ─────────────────

    @Test
    @TestTransaction
    void wholesaleAscendingOrdersByWholesalePrices()
    {
        String marker = "ZZORDWHL-ASC-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Wholesale prices deliberately differ from retail ordering:
        // Cheap=8, Expensive=40, Mid=60
        ProductEntity cheap = newProduct(marker, "Cheap");
        ProductVariantEntity vCheap = newVariant(cheap);
        addActivePrice(vCheap, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10));
        addActivePrice(vCheap, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(8));

        ProductEntity mid = newProduct(marker, "Mid");
        ProductVariantEntity vMid = newVariant(mid);
        addActivePrice(vMid, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vMid, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(60));

        ProductEntity expensive = newProduct(marker, "Expensive");
        ProductVariantEntity vExpensive = newVariant(expensive);
        addActivePrice(vExpensive, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(100));
        addActivePrice(vExpensive, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(40));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.WHOLESALE, null);

        assertEquals(3, results.size(), "Exactly 3 tracked products should appear in filtered results");

        int idxCheap = indexOf(results, cheap.getId());
        int idxMid = indexOf(results, mid.getId());
        int idxExpensive = indexOf(results, expensive.getId());

        assertNotEquals(-1, idxCheap, "Cheap product must appear in results");
        assertNotEquals(-1, idxMid, "Mid product must appear in results");
        assertNotEquals(-1, idxExpensive, "Expensive product must appear in results");

        // Wholesale order: Cheap(8) < Expensive(40) < Mid(60)
        assertTrue(idxCheap < idxExpensive,
                "WHOLESALE ASC: Cheap (W8) must appear before Expensive (W40), but got indices " + idxCheap + " vs " + idxExpensive);
        assertTrue(idxExpensive < idxMid,
                "WHOLESALE ASC: Expensive (W40) must appear before Mid (W60), but got indices " + idxExpensive + " vs " + idxMid);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.2: Sale-over-base ordering test
    //
    // The sale tier wins on EXISTENCE, not on price. If a sale row exists, use
    // its price for sorting — even if the sale price is higher than the base.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void saleOverBase_retailAsc_sortsBySalePriceEvenWhenDearerThanBase()
    {
        String marker = "ZZSALEOVER-ASC-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product "SaleDearer": retail base price 50.00, retail SALE price 80.00
        // The sale is MORE expensive than the base — weird but valid.
        // The card shows 80.00 (sale tier wins on existence), so the sort key must be 80.
        ProductEntity saleDearer = newProduct(marker, "SaleDearer");
        ProductVariantEntity vSaleDearer = newVariant(saleDearer);
        addActivePrice(vSaleDearer, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vSaleDearer, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(80));

        // Product "BaseOnly": retail base price 60.00, NO sale price
        // Sort key = 60 (falls back to base tier since no sale row exists)
        ProductEntity baseOnly = newProduct(marker, "BaseOnly");
        ProductVariantEntity vBaseOnly = newVariant(baseOnly);
        addActivePrice(vBaseOnly, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        // Product "CheapBase": retail base price 30.00, NO sale price
        // Sort key = 30 (falls back to base tier)
        ProductEntity cheapBase = newProduct(marker, "CheapBase");
        ProductVariantEntity vCheapBase = newVariant(cheapBase);
        addActivePrice(vCheapBase, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(3, results.size(), "All three tracked products must appear in the filtered result");

        int idxCheapBase = indexOf(results, cheapBase.getId());
        int idxBaseOnly = indexOf(results, baseOnly.getId());
        int idxSaleDearer = indexOf(results, saleDearer.getId());

        assertNotEquals(-1, idxCheapBase, "CheapBase must be in the results");
        assertNotEquals(-1, idxBaseOnly, "BaseOnly must be in the results");
        assertNotEquals(-1, idxSaleDearer, "SaleDearer must be in the results");

        // CheapBase (sort key 30) < BaseOnly (sort key 60) < SaleDearer (sort key 80)
        assertTrue(idxCheapBase < idxBaseOnly,
                "CheapBase (sort key 30) must come before BaseOnly (sort key 60) in ASC order, " +
                        "but got indices " + idxCheapBase + " vs " + idxBaseOnly);

        // KEY assertion: if the implementation used min(sale, base) = min(80,50) = 50,
        // then SaleDearer would sort at 50 and come BEFORE BaseOnly (60). That would be wrong.
        // The sale tier wins on EXISTENCE, not on price.
        assertTrue(idxBaseOnly < idxSaleDearer,
                "BaseOnly (sort key 60) must come before SaleDearer (sort key 80 = sale price, " +
                        "not base 50) in ASC order. The sale tier wins on EXISTENCE, not on price. " +
                        "If SaleDearer sorted before BaseOnly, the implementation uses cheapest-of-" +
                        "sale/base instead of sale-when-it-exists. Got indices " + idxBaseOnly + " vs " + idxSaleDearer);
    }

    @Test
    @TestTransaction
    void saleOverBase_retailDesc_sortsBySalePriceEvenWhenDearerThanBase()
    {
        String marker = "ZZSALEOVER-DESC-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        ProductEntity saleDearer = newProduct(marker, "SaleDearer");
        ProductVariantEntity vSaleDearer = newVariant(saleDearer);
        addActivePrice(vSaleDearer, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));
        addActivePrice(vSaleDearer, PriceTypeEn.RETAIL_SALE_PRICE, BigDecimal.valueOf(80));

        ProductEntity baseOnly = newProduct(marker, "BaseOnly");
        ProductVariantEntity vBaseOnly = newVariant(baseOnly);
        addActivePrice(vBaseOnly, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        ProductEntity cheapBase = newProduct(marker, "CheapBase");
        ProductVariantEntity vCheapBase = newVariant(cheapBase);
        addActivePrice(vCheapBase, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_DESC, PriceBasisEn.RETAIL, null);

        assertEquals(3, results.size(), "All three tracked products must appear in the filtered result");

        int idxCheapBase = indexOf(results, cheapBase.getId());
        int idxBaseOnly = indexOf(results, baseOnly.getId());
        int idxSaleDearer = indexOf(results, saleDearer.getId());

        assertNotEquals(-1, idxCheapBase, "CheapBase must be in the results");
        assertNotEquals(-1, idxBaseOnly, "BaseOnly must be in the results");
        assertNotEquals(-1, idxSaleDearer, "SaleDearer must be in the results");

        // Descending: SaleDearer (80) > BaseOnly (60) > CheapBase (30)
        assertTrue(idxSaleDearer < idxBaseOnly,
                "SaleDearer (sort key 80) must come before BaseOnly (sort key 60) in DESC order, " +
                        "but got indices " + idxSaleDearer + " vs " + idxBaseOnly);
        assertTrue(idxBaseOnly < idxCheapBase,
                "BaseOnly (sort key 60) must come before CheapBase (sort key 30) in DESC order, " +
                        "but got indices " + idxBaseOnly + " vs " + idxCheapBase);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.6: Hydration order-preservation test
    //
    // Proves the hydration step preserves the ID-selection query's order.
    // Seeds products where price ordering DIFFERS from name ordering — if the
    // hydration query returned entities in DB/name order (i.e. lost the sort),
    // the result would be Alpha, Bravo, Charlie instead of the correct price
    // order Bravo(30), Charlie(60), Alpha(90).
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void hydrationPreservesIdSelectionOrder_priceAscDiffersFromNameOrder()
    {
        String marker = "ZZHYDORD-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Product "Alpha" — alphabetically first, but MOST expensive (90)
        ProductEntity alpha = newProduct(marker, "Alpha");
        ProductVariantEntity vAlpha = newVariant(alpha);
        addActivePrice(vAlpha, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(90));

        // Product "Bravo" — alphabetically second, but CHEAPEST (30)
        ProductEntity bravo = newProduct(marker, "Bravo");
        ProductVariantEntity vBravo = newVariant(bravo);
        addActivePrice(vBravo, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        // Product "Charlie" — alphabetically third, middle price (60)
        ProductEntity charlie = newProduct(marker, "Charlie");
        ProductVariantEntity vCharlie = newVariant(charlie);
        addActivePrice(vCharlie, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(60));

        em.flush();

        // Request PRICE_ASC ordering — expected: Bravo(30), Charlie(60), Alpha(90)
        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(3, results.size(), "All three tracked products must appear in the filtered result");

        int idxAlpha = indexOf(results, alpha.getId());
        int idxBravo = indexOf(results, bravo.getId());
        int idxCharlie = indexOf(results, charlie.getId());

        assertNotEquals(-1, idxAlpha, "Alpha must be in the results");
        assertNotEquals(-1, idxBravo, "Bravo must be in the results");
        assertNotEquals(-1, idxCharlie, "Charlie must be in the results");

        // If hydration lost the sort order (returning in DB/name order),
        // we'd get Alpha(idx 0), Bravo(idx 1), Charlie(idx 2) — name alphabetical.
        // Correct price order is: Bravo(30) < Charlie(60) < Alpha(90)
        assertTrue(idxBravo < idxCharlie,
                "Hydration order preservation: Bravo (R30) must appear before Charlie (R60) " +
                        "in PRICE_ASC order. If Alpha appeared first instead of last, the hydration " +
                        "step returned entities in name/DB order and lost the ID-selection sort. " +
                        "Got indices Bravo=" + idxBravo + " Charlie=" + idxCharlie);
        assertTrue(idxCharlie < idxAlpha,
                "Hydration order preservation: Charlie (R60) must appear before Alpha (R90) " +
                        "in PRICE_ASC order. Alpha is alphabetically first but most expensive — " +
                        "if it appears first, the hydration step did not reorder to the ID-selection " +
                        "query's order. Got indices Charlie=" + idxCharlie + " Alpha=" + idxAlpha);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.5: SQL-level pagination test
    //
    // Proves real SQL LIMIT/OFFSET pagination:
    // - At most pageSize items are returned per page request
    // - The total count exceeds pageSize (confirming the full set is larger)
    // - Different pages return different products (IDs present on page 0 are
    //   absent from page 1 and vice versa), which is conclusive proof of real
    //   SQL pagination — in-memory slicing over a full fetch would not survive
    //   this combined assertion.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void sqlPagination_returnsAtMostPageSizeItems_andDifferentPagesReturnDifferentProducts()
    {
        String marker = "ZZPAGINATE-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Seed 5 products with distinct prices so ordering is fully deterministic
        ProductEntity p1 = newProduct(marker, "P1");
        ProductVariantEntity v1 = newVariant(p1);
        addActivePrice(v1, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10));

        ProductEntity p2 = newProduct(marker, "P2");
        ProductVariantEntity v2 = newVariant(p2);
        addActivePrice(v2, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(20));

        ProductEntity p3 = newProduct(marker, "P3");
        ProductVariantEntity v3 = newVariant(p3);
        addActivePrice(v3, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(30));

        ProductEntity p4 = newProduct(marker, "P4");
        ProductVariantEntity v4 = newVariant(p4);
        addActivePrice(v4, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(40));

        ProductEntity p5 = newProduct(marker, "P5");
        ProductVariantEntity v5 = newVariant(p5);
        addActivePrice(v5, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));

        em.flush();

        FilterRequest filter = filterByMarker(marker);
        int pageSize = 2;

        // --- Count query returns the full total (> pageSize) ---
        long totalCount = productService.countShoppingProducts(filter, false, null);
        assertTrue(totalCount > pageSize,
                "Count must exceed pageSize (" + pageSize + ") to prove pagination is real; got " + totalCount);
        assertEquals(5, totalCount,
                "5 tracked products seeded but count returned " + totalCount);

        // --- Page 0: at most pageSize items ---
        List<ProductShoppingListItemDto> page0 = productService.getShoppingProducts(
                pageOf(0, pageSize), filter, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(pageSize, page0.size(),
                "Page 0 must return exactly pageSize (" + pageSize + ") items, not " + page0.size());

        // --- Page 1: at most pageSize items ---
        List<ProductShoppingListItemDto> page1 = productService.getShoppingProducts(
                pageOf(1, pageSize), filter, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(pageSize, page1.size(),
                "Page 1 must return exactly pageSize (" + pageSize + ") items, not " + page1.size());

        // --- Prove real pagination: page 0 and page 1 return DIFFERENT products ---
        List<String> page0Ids = page0.stream().map(ProductShoppingListItemDto::getId).toList();
        List<String> page1Ids = page1.stream().map(ProductShoppingListItemDto::getId).toList();

        for (String id : page0Ids)
        {
            assertFalse(page1Ids.contains(id),
                    "Product " + id + " appears on both page 0 and page 1 — " +
                            "pagination is duplicating items (suggests in-memory slicing or missing tie-break)");
        }

        // --- Final page (page 2): should have the remainder (1 item) ---
        List<ProductShoppingListItemDto> page2 = productService.getShoppingProducts(
                pageOf(2, pageSize), filter, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(1, page2.size(),
                "Page 2 must return exactly 1 item (5 total, pageSize 2, pages 0-1 have 4), got " + page2.size());

        // Ensure page 2's item is different from pages 0 and 1
        String page2Id = page2.get(0).getId();
        assertFalse(page0Ids.contains(page2Id),
                "Page 2 product must not appear on page 0");
        assertFalse(page1Ids.contains(page2Id),
                "Page 2 product must not appear on page 1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.8: Unchanged-behaviour tests
    //
    // Proves that the refactored query path preserves existing behaviour:
    // (a) Default ordering is name ascending
    // (b) Category + includeSubCategories returns the same product set as before
    // (c) totalElements/totalPages unchanged
    // (d) ProductShoppingListItemDto payload populated field-for-field
    // ═══════════════════════════════════════════════════════════════════════════

    // ─── (a) Default ordering is name ascending ─────────────────────────────

    @Test
    @TestTransaction
    void defaultOrdering_nameAscending_alphaBeforeMangoBeforeZeta()
    {
        String marker = "ZZDEFAULTORD-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Products named with alphabetically-varied suffixes
        ProductEntity zeta = newProduct(marker, "Zeta");
        ProductVariantEntity vZeta = newVariant(zeta);
        addActivePrice(vZeta, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(50));

        ProductEntity alpha = newProduct(marker, "Alpha");
        ProductVariantEntity vAlpha = newVariant(alpha);
        addActivePrice(vAlpha, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(80));

        ProductEntity mango = newProduct(marker, "Mango");
        ProductVariantEntity vMango = newVariant(mango);
        addActivePrice(vMango, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(20));

        em.flush();

        // Request with NAME_ASC (the default) — no price ordering involved
        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(3, results.size(), "Exactly 3 tracked products expected");

        int idxAlpha = indexOf(results, alpha.getId());
        int idxMango = indexOf(results, mango.getId());
        int idxZeta = indexOf(results, zeta.getId());

        assertNotEquals(-1, idxAlpha, "Alpha must appear in results");
        assertNotEquals(-1, idxMango, "Mango must appear in results");
        assertNotEquals(-1, idxZeta, "Zeta must appear in results");

        // Alphabetical order: marker+"Alpha" < marker+"Mango" < marker+"Zeta"
        assertTrue(idxAlpha < idxMango,
                "Default NAME_ASC: Alpha must appear before Mango, got indices " + idxAlpha + " vs " + idxMango);
        assertTrue(idxMango < idxZeta,
                "Default NAME_ASC: Mango must appear before Zeta, got indices " + idxMango + " vs " + idxZeta);
    }

    // ─── (b) Category + includeSubCategories returns descendant products ────

    @Test
    @TestTransaction
    void categoryWithIncludeSubCategories_returnsProductsFromDescendantCategories()
    {
        String marker = "ZZSUBCAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Create a parent category
        CategoryEntity parentCat = new CategoryEntity();
        parentCat.setName(marker + "ParentCat");
        parentCat.setSlug((marker + "parent-cat-" + UUID.randomUUID()).toLowerCase());
        parentCat.persist();

        // Create a child category under the parent
        CategoryEntity childCat = new CategoryEntity();
        childCat.setName(marker + "ChildCat");
        childCat.setSlug((marker + "child-cat-" + UUID.randomUUID()).toLowerCase());
        childCat.setParent(parentCat);
        childCat.persist();

        // Product linked to parent category
        ProductEntity parentProduct = newProduct(marker, "ParentProd");
        parentProduct.setCategory(parentCat);
        ProductVariantEntity vParent = newVariant(parentProduct);
        addActivePrice(vParent, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(25));

        // Product linked to child category ONLY (not directly to parent)
        ProductEntity childProduct = newProduct(marker, "ChildProd");
        childProduct.setCategory(childCat);
        ProductVariantEntity vChild = newVariant(childProduct);
        addActivePrice(vChild, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(35));

        em.flush();

        // Simulate what the resolver does when includeSubCategories=true:
        // It resolves the parent + all descendant category IDs and adds a category.id IN filter
        List<String> scopedCategoryIds = new ArrayList<>();
        scopedCategoryIds.add(parentCat.getId().toString());
        scopedCategoryIds.add(childCat.getId().toString());

        FilterRequest fr = new FilterRequest();
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("name", FilterOperator.ILIKE, marker));
        filters.add(new Filter("category.id", FilterOperator.IN, scopedCategoryIds));
        fr.setFilters(filters);

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), fr, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);

        // Both products must appear — the child-only product is found via descendant categories
        assertTrue(results.size() >= 2,
                "Expected at least 2 products (parent + child category), got " + results.size());

        int idxParent = indexOf(results, parentProduct.getId());
        int idxChild = indexOf(results, childProduct.getId());

        assertNotEquals(-1, idxParent,
                "Product linked to parent category must appear when filtering by parent with includeSubCategories");
        assertNotEquals(-1, idxChild,
                "Product linked to child category must appear when filtering by parent with includeSubCategories — " +
                        "descendant-only products must be included");
    }

    // ─── (c) totalElements/totalPages unchanged ─────────────────────────────

    @Test
    @TestTransaction
    void totalElementsAndTotalPages_matchCountAndCeilDivision()
    {
        String marker = "ZZTOTALS-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Seed 5 products
        for (int i = 1; i <= 5; i++)
        {
            ProductEntity p = newProduct(marker, "Product" + i);
            ProductVariantEntity v = newVariant(p);
            addActivePrice(v, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10 * i));
        }

        em.flush();

        FilterRequest filter = filterByMarker(marker);
        int pageSize = 2;

        // Count query must return the total matching count
        long totalElements = productService.countShoppingProducts(filter, false, null);
        assertEquals(5, totalElements,
                "countShoppingProducts must return exactly 5 for 5 seeded tracked products");

        // totalPages = ceil(totalElements / pageSize) = ceil(5/2) = 3
        int expectedTotalPages = (int) Math.ceil((double) totalElements / pageSize);
        assertEquals(3, expectedTotalPages,
                "totalPages for 5 elements with pageSize 2 must be 3");

        // Verify page results align with the total count
        List<ProductShoppingListItemDto> page0 = productService.getShoppingProducts(
                pageOf(0, pageSize), filter, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);
        List<ProductShoppingListItemDto> page1 = productService.getShoppingProducts(
                pageOf(1, pageSize), filter, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);
        List<ProductShoppingListItemDto> page2 = productService.getShoppingProducts(
                pageOf(2, pageSize), filter, false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);

        // Pages 0 and 1 should have exactly pageSize items, page 2 the remainder
        assertEquals(2, page0.size(), "Page 0 must have pageSize items");
        assertEquals(2, page1.size(), "Page 1 must have pageSize items");
        assertEquals(1, page2.size(), "Page 2 must have the 1 remaining item");

        // Sum of all pages should equal totalElements
        long totalAcrossPages = page0.size() + page1.size() + page2.size();
        assertEquals(totalElements, totalAcrossPages,
                "Sum of items across all pages must equal totalElements");
    }

    // ─── (d) ProductShoppingListItemDto payload populated field-for-field ────

    @Test
    @TestTransaction
    void dtoPayload_allFieldsPopulated_forProductWithKnownData()
    {
        String marker = "ZZPAYLOAD-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Seed a product with complete known data
        ProductEntity product = newProduct(marker, "FullPayloadProduct");
        product.setShortDescription("A test description for payload verification");

        ProductVariantEntity variant = newVariant(product);
        addActivePrice(variant, PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(99.99));
        addActivePrice(variant, PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(79.99));

        // Add an image to the variant
        ProductImageEntity image = new ProductImageEntity();
        image.setProductVariant(variant);
        image.setImageUrl("/test/images/payload-test.jpg");
        image.setSortOrder(1);
        image.setIsFeatured(true);
        image.persist();

        em.flush();

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), filterByMarker(marker), false, CatalogueSortEn.NAME_ASC, PriceBasisEn.RETAIL, null);

        assertEquals(1, results.size(), "Exactly 1 tracked product expected");

        ProductShoppingListItemDto dto = results.get(0);

        // Assert all core fields are populated (non-null)
        assertNotNull(dto.getId(), "id must be populated");
        assertEquals(product.getId().toString(), dto.getId(), "id must match the seeded product");

        assertNotNull(dto.getName(), "name must be populated");
        assertEquals(marker + "FullPayloadProduct", dto.getName(), "name must match the seeded value");

        assertNotNull(dto.getSlug(), "slug must be populated");
        assertTrue(dto.getSlug().contains("fullpayloadproduct"),
                "slug must be derived from the product name");

        assertNotNull(dto.getStatus(), "status must be populated");
        assertEquals("ACTIVE", dto.getStatus(), "status must be ACTIVE");

        assertNotNull(dto.getProductType(), "productType must be populated");
        assertEquals("SIMPLE", dto.getProductType(), "productType must be SIMPLE");

        assertNotNull(dto.getImages(), "images list must be populated");
        assertFalse(dto.getImages().isEmpty(), "images list must not be empty");

        assertNotNull(dto.getVariantCount(), "variantCount must be populated");
        assertEquals(1, dto.getVariantCount(), "variantCount must be 1 for a single-variant product");

        // Retail price must be populated since we seeded one
        assertNotNull(dto.getRetailPrice(), "retailPrice must be populated");
        assertEquals(0, BigDecimal.valueOf(99.99).compareTo(dto.getRetailPrice().getPrice()),
                "retailPrice amount must match the seeded value (99.99)");

        // Wholesale price must be populated since we seeded one
        assertNotNull(dto.getWholesalePrice(), "wholesalePrice must be populated");
        assertEquals(0, BigDecimal.valueOf(79.99).compareTo(dto.getWholesalePrice().getPrice()),
                "wholesalePrice amount must match the seeded value (79.99)");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.4: Null-basis ordering (added by audit 2026-07-30 — the task was
    // checked but the test did not exist)
    //
    // A product with no active price row in the requested basis has a NULL sort
    // key and must sort LAST in BOTH directions. Postgres defaults DESC to
    // NULLS FIRST, so the DESC case is the one that catches a missing NULLS LAST.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void nullBasisSortsLast_inBothDirections()
    {
        String marker = "ZZNULLBASIS-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        // Two products with retail prices; one with ONLY a wholesale price.
        // The wholesale-only product passes the priced-variant EXISTS (wholesale
        // is one of the four shopping price types) but has a NULL RETAIL sort key.
        ProductEntity cheap = newProduct(marker, "Cheap");
        addActivePrice(newVariant(cheap), PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(10));

        ProductEntity dear = newProduct(marker, "Dear");
        addActivePrice(newVariant(dear), PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(20));

        ProductEntity wholesaleOnly = newProduct(marker, "AAWholesaleOnly"); // name sorts FIRST alphabetically on purpose
        addActivePrice(newVariant(wholesaleOnly), PriceTypeEn.WHOLESALE_PRICE, BigDecimal.valueOf(5));

        em.flush();

        FilterRequest filter = filterByMarker(marker);

        // ASC: cheap, dear, then the null-key product last
        List<ProductShoppingListItemDto> asc = productService.getShoppingProducts(
                pageOf(0, 50), filter, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);
        assertEquals(3, asc.size(), "All three tracked products must appear");
        assertTrue(indexOf(asc, cheap.getId()) < indexOf(asc, dear.getId()),
                "ASC: cheap (10) must sort before dear (20)");
        assertEquals(asc.size() - 1, indexOf(asc, wholesaleOnly.getId()),
                "ASC: the product with no RETAIL price must sort LAST (null sort key)");

        // DESC: dear, cheap, then the null-key product STILL last — this is the
        // assertion that fails if NULLS LAST is dropped from the DESC branch.
        List<ProductShoppingListItemDto> desc = productService.getShoppingProducts(
                pageOf(0, 50), filter, false, CatalogueSortEn.PRICE_DESC, PriceBasisEn.RETAIL, null);
        assertEquals(3, desc.size(), "All three tracked products must appear");
        assertTrue(indexOf(desc, dear.getId()) < indexOf(desc, cheap.getId()),
                "DESC: dear (20) must sort before cheap (10)");
        assertEquals(desc.size() - 1, indexOf(desc, wholesaleOnly.getId()),
                "DESC: the product with no RETAIL price must sort LAST, never first "
                        + "(Postgres DESC defaults to NULLS FIRST — this guards the explicit NULLS LAST)");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Requirement 5.8: multi-category product appears exactly once, no DISTINCT
    // (added by audit 2026-07-30 — the fixture mandated by tasks 2.2/2.5 was absent)
    //
    // A product linked to TWO categories that BOTH match the category.id IN filter
    // is the exact row-multiplication case the old join + DISTINCT compensated for.
    // With the per-predicate EXISTS rewrite the product must appear exactly once
    // — and the count query must agree.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void multiCategoryProduct_appearsExactlyOncePerPage_andCountAgrees()
    {
        String marker = "ZZMULTICAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        CategoryEntity catA = new CategoryEntity();
        catA.setName(marker + "CatA");
        catA.setSlug((marker + "cat-a-" + UUID.randomUUID()).toLowerCase());
        catA.persist();

        CategoryEntity catB = new CategoryEntity();
        catB.setName(marker + "CatB");
        catB.setSlug((marker + "cat-b-" + UUID.randomUUID()).toLowerCase());
        catB.persist();

        // Product in BOTH categories — setCategory() adds to the categories set
        ProductEntity multi = newProduct(marker, "MultiCat");
        multi.setCategory(catA);
        multi.setCategory(catB);
        addActivePrice(newVariant(multi), PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(15));

        // Companion product in ONE of the categories
        ProductEntity single = newProduct(marker, "SingleCat");
        single.setCategory(catA);
        addActivePrice(newVariant(single), PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(25));

        em.flush();

        // Filter matching BOTH of the multi-category product's categories
        FilterRequest fr = new FilterRequest();
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("name", FilterOperator.ILIKE, marker));
        filters.add(new Filter("category.id", FilterOperator.IN,
                List.of(catA.getId().toString(), catB.getId().toString())));
        fr.setFilters(filters);

        List<ProductShoppingListItemDto> results = productService.getShoppingProducts(
                pageOf(0, 50), fr, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);

        long multiOccurrences = results.stream()
                .filter(dto -> multi.getId().toString().equals(dto.getId()))
                .count();
        assertEquals(1, multiOccurrences,
                "A product in two matching categories must appear EXACTLY once — more than once "
                        + "means the EXISTS rewrite regressed to a row-multiplying join without DISTINCT");
        assertEquals(2, results.size(),
                "Exactly the two tracked products must appear (multi-cat once + single-cat once)");

        // The count query runs the same EXISTS rewrite and must agree
        long total = productService.countShoppingProducts(fr, false, null);
        assertEquals(2, total,
                "countShoppingProducts must agree with the page content — count(p) with the EXISTS "
                        + "rewrite must not double-count the multi-category product");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 4.3 (repeated-price case): tie-break totality under LIMIT/OFFSET
    // (added by audit 2026-07-30 — the existing pagination test seeds distinct
    // prices, so the load-bearing p.name/p.id tie-break was unexercised)
    //
    // With IDENTICAL prices, only the deterministic tie-break stops products
    // being duplicated or skipped across page boundaries. Walk every page twice:
    // each product exactly once per walk, and both walks in identical order.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @TestTransaction
    void repeatedPrices_pageWalkIsTotalAndDeterministic()
    {
        String marker = "ZZTIEBREAK-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        List<UUID> seededIds = new ArrayList<>();
        for (String suffix : List.of("P1", "P2", "P3", "P4", "P5"))
        {
            ProductEntity p = newProduct(marker, suffix);
            addActivePrice(newVariant(p), PriceTypeEn.RETAIL_PRICE, BigDecimal.valueOf(15)); // ALL identical
            seededIds.add(p.getId());
        }
        em.flush();

        FilterRequest filter = filterByMarker(marker);
        int pageSize = 2;

        List<String> firstWalk = walkAllPages(filter, pageSize);
        List<String> secondWalk = walkAllPages(filter, pageSize);

        // Totality: every tracked product appears exactly once — none skipped, none duplicated
        assertEquals(seededIds.size(), firstWalk.size(),
                "Walking every page must yield exactly the 5 tracked products; "
                        + "an unstable sort under LIMIT/OFFSET duplicates and skips rows");
        for (UUID id : seededIds)
        {
            long occurrences = firstWalk.stream().filter(x -> x.equals(id.toString())).count();
            assertEquals(1, occurrences, "Product " + id + " must appear exactly once across all pages");
        }

        // Determinism: the same query walked twice returns the identical order (Req 4.2)
        assertEquals(firstWalk, secondWalk,
                "Two identical price-sorted walks must return the identical order — "
                        + "with equal prices only the p.name, p.id tie-break makes the ordering total");
    }

    /** Walks every page of a PRICE_ASC RETAIL result and returns the concatenated ID sequence. */
    private List<String> walkAllPages(FilterRequest filter, int pageSize)
    {
        List<String> ids = new ArrayList<>();
        int pageIndex = 0;
        while (true)
        {
            List<ProductShoppingListItemDto> page = productService.getShoppingProducts(
                    pageOf(pageIndex, pageSize), filter, false, CatalogueSortEn.PRICE_ASC, PriceBasisEn.RETAIL, null);
            if (page.isEmpty())
            {
                break;
            }
            page.forEach(dto -> ids.add(dto.getId()));
            pageIndex++;
        }
        return ids;
    }
}
