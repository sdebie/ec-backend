package org.ecommerce.backend.service;

// Feature: featured-products-list, Properties 5, 6, 8: query correctness (JPQL)

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration tests for {@link FeaturedProductService} query correctness.
 *
 * PanacheMock cannot validate the JPQL in getFeaturedProductsForAdmin /
 * getFeaturedShoppingProducts (filter, ordering, {@code MEMBER OF categories}); those
 * are exercised here against the real datasource. Each test persists real
 * {@link ProductEntity} rows inside a {@link TestTransaction} (rolled back afterward, so
 * the shared dev DB is never mutated) and asserts the REAL service returns correctly
 * ordered / filtered results.
 *
 * A unique per-test marker isolates assertions from any pre-existing rows in the DB.
 *
 * Covers:
 *   Property 5: Admin List Ordering Invariant  (Requirements 3.1, 3.2)
 *   Property 6: Storefront Query Filter Correctness  (Requirements 4.1)
 *   Property 8: Category Filter Correctness  (Requirements 4.3)
 */
@QuarkusTest
class FeaturedProductServiceIT {

    @Inject
    FeaturedProductService featuredProductService;

    @Inject
    EntityManager em;

    private ProductEntity newProduct(String marker, String nameSuffix, boolean featured, ProductStatusEn status) {
        ProductEntity p = new ProductEntity();
        p.name = marker + nameSuffix;
        p.slug = (marker + nameSuffix + "-" + UUID.randomUUID()).toLowerCase();
        p.status = status;
        p.isFeatured = featured;
        p.productType = ProductTypeEn.SIMPLE;
        p.persist();
        return p;
    }

    // ── Property 5: Admin List Ordering Invariant ───────────────────────────

    @Test
    @TestTransaction
    void adminListReturnsAllFeaturedRegardlessOfStatusSortedByName() {
        String marker = "ZZITADMIN-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        newProduct(marker, "Apple", true, ProductStatusEn.ACTIVE);
        newProduct(marker, "Cherry", true, ProductStatusEn.PENDING);
        newProduct(marker, "Banana", true, ProductStatusEn.DISABLED);
        newProduct(marker, "Damson", false, ProductStatusEn.ACTIVE); // not featured
        em.flush();

        List<AdminProductListItemDto> all = featuredProductService.getFeaturedProductsForAdmin();

        // Our featured products (any status) all appear, in name order; the non-featured one does not.
        // (Ordering is asserted on our controlled ASCII subset; the DB applies ORDER BY name ASC
        // under its own collation, which need not match Java String.compareTo on unrelated seed rows.)
        List<String> mine = all.stream()
                .map(d -> d.name)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Apple", marker + "Banana", marker + "Cherry"), mine,
                "All featured products must be returned regardless of status, sorted by name asc");
        assertFalse(mine.contains(marker + "Damson"), "Non-featured product must not appear");
    }

    // ── Property 6: Storefront Query Filter Correctness ─────────────────────

    @Test
    @TestTransaction
    void storefrontListReturnsOnlyFeaturedActiveSortedByName() {
        String marker = "ZZITSF-" + UUID.randomUUID().toString().substring(0, 8) + "-";

        newProduct(marker, "Apple", true, ProductStatusEn.ACTIVE);   // qualifies
        newProduct(marker, "Cherry", true, ProductStatusEn.PENDING); // not ACTIVE
        newProduct(marker, "Banana", true, ProductStatusEn.DISABLED);// not ACTIVE
        newProduct(marker, "Damson", false, ProductStatusEn.ACTIVE); // not featured
        em.flush();

        // Large limit so nothing is dropped by paging.
        List<ProductShoppingListItemDto> all = featuredProductService.getFeaturedShoppingProducts(50, null);

        // Every returned product is ACTIVE.
        for (ProductShoppingListItemDto d : all) {
            assertEquals(ProductStatusEn.ACTIVE.name(), d.status,
                    "Storefront list must only contain ACTIVE products");
        }

        List<String> mine = all.stream()
                .map(d -> d.name)
                .filter(n -> n.startsWith(marker))
                .collect(Collectors.toList());

        assertEquals(List.of(marker + "Apple"), mine,
                "Only the featured + ACTIVE product must appear from this test set");
    }

    // ── Property 8: Category Filter Correctness ─────────────────────────────

    @Test
    @TestTransaction
    void categoryFilterReturnsOnlyFeaturedActiveMembersOfCategory() {
        String marker = "ZZITCAT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        String slug = "zzitcat-slug-" + UUID.randomUUID();

        CategoryEntity category = new CategoryEntity();
        category.name = marker + "Category";
        category.slug = slug;
        category.persist();

        ProductEntity inCatActiveFeatured = newProduct(marker, "Apple", true, ProductStatusEn.ACTIVE);
        ProductEntity inCatPendingFeatured = newProduct(marker, "Cherry", true, ProductStatusEn.PENDING);
        ProductEntity inCatActiveNotFeatured = newProduct(marker, "Damson", false, ProductStatusEn.ACTIVE);
        ProductEntity notInCatActiveFeatured = newProduct(marker, "Elder", true, ProductStatusEn.ACTIVE);

        inCatActiveFeatured.setCategory(category);
        inCatPendingFeatured.setCategory(category);
        inCatActiveNotFeatured.setCategory(category);
        // notInCatActiveFeatured deliberately has no category
        em.flush();

        List<ProductShoppingListItemDto> result =
                featuredProductService.getFeaturedShoppingProducts(50, slug);

        // The category is unique to this test, so the entire result is ours: only the
        // featured + ACTIVE member of the category qualifies.
        List<String> names = result.stream().map(d -> d.name).collect(Collectors.toList());
        assertEquals(List.of(marker + "Apple"), names,
                "Only featured + ACTIVE products that are MEMBER OF the category must be returned");

        assertFalse(names.contains(marker + "Cherry"), "PENDING member must be excluded");
        assertFalse(names.contains(marker + "Damson"), "Non-featured member must be excluded");
        assertFalse(names.contains(marker + "Elder"), "Featured/ACTIVE non-member must be excluded");
    }

    @Test
    @TestTransaction
    void unknownCategorySlugReturnsEmptyList() {
        String marker = "ZZITCATX-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        newProduct(marker, "Apple", true, ProductStatusEn.ACTIVE);
        em.flush();

        List<ProductShoppingListItemDto> result =
                featuredProductService.getFeaturedShoppingProducts(50, "no-such-category-" + UUID.randomUUID());

        assertTrue(result.isEmpty(), "Unknown category slug must yield an empty list");
    }
}
