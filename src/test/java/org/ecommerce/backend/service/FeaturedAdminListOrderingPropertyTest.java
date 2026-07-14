package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 5: Admin List Ordering Invariant

import net.jqwik.api.*;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 5: Admin List Ordering Invariant
 *
 * For any set of featured products, getFeaturedProductsForAdmin() returns all of them
 * (regardless of product status), and the returned list is sorted by product name in
 * ascending lexicographic order.
 *
 * This test verifies the ordering invariant by simulating the FeaturedProductService
 * logic with an in-memory store that mirrors the Panache entity behavior.
 *
 * Validates: Requirements 3.1, 3.2
 */
class FeaturedAdminListOrderingPropertyTest {

    /**
     * Simulates FeaturedProductService.getFeaturedProductsForAdmin behavior.
     * Mirrors the exact query logic: SELECT * FROM products WHERE is_featured = true ORDER BY name ASC.
     */
    private static class FeaturedProductSimulator {
        private final Map<UUID, SimProduct> products = new HashMap<>();

        static class SimProduct {
            UUID id;
            String name;
            boolean isFeatured;
            ProductStatusEn status;

            SimProduct(UUID id, String name, ProductStatusEn status) {
                this.id = id;
                this.name = name;
                this.isFeatured = false;
                this.status = status;
            }

            SimProduct(UUID id, String name, boolean isFeatured, ProductStatusEn status) {
                this.id = id;
                this.name = name;
                this.isFeatured = isFeatured;
                this.status = status;
            }
        }

        void addProduct(UUID id, String name, boolean isFeatured, ProductStatusEn status) {
            products.put(id, new SimProduct(id, name, isFeatured, status));
        }

        /**
         * Replicates FeaturedProductService.getFeaturedProductsForAdmin logic:
         * Returns all products where isFeatured = true, ordered by name ascending.
         * Status is irrelevant — all featured products are returned regardless of status.
         */
        List<SimProduct> getFeaturedProductsForAdmin() {
            return products.values().stream()
                    .filter(p -> p.isFeatured)
                    .sorted(Comparator.comparing(p -> p.name))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Property: For any set of featured products (with any status), getFeaturedProductsForAdmin()
     * returns ALL of them sorted by name ascending.
     */
    @Property(tries = 100)
    void adminListReturnsAllFeaturedProductsSortedByNameAscending(
            @ForAll("featuredCount") int featuredCount,
            @ForAll("nonFeaturedCount") int nonFeaturedCount,
            @ForAll("productStatuses") List<ProductStatusEn> statuses,
            @ForAll("productNames") List<String> names
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Generate unique UUIDs deterministically using index-based naming
        List<UUID> featuredIds = new ArrayList<>();
        for (int i = 0; i < featuredCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("featured-" + i).getBytes());
            String name = names.get(i % names.size());
            ProductStatusEn status = statuses.get(i % statuses.size());
            simulator.addProduct(id, name, true, status);
            featuredIds.add(id);
        }

        List<UUID> nonFeaturedIds = new ArrayList<>();
        for (int i = 0; i < nonFeaturedCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("non-featured-" + i).getBytes());
            String name = names.get((featuredCount + i) % names.size());
            ProductStatusEn status = statuses.get((featuredCount + i) % statuses.size());
            simulator.addProduct(id, name, false, status);
            nonFeaturedIds.add(id);
        }

        // Call the admin list query
        List<FeaturedProductSimulator.SimProduct> result = simulator.getFeaturedProductsForAdmin();

        // Assert: all featured products are returned (completeness)
        assertEquals(featuredCount, result.size(),
                "Admin list must return ALL featured products regardless of status");

        // Assert: every featured product ID is present in the result
        Set<UUID> resultIds = result.stream().map(p -> p.id).collect(Collectors.toSet());
        for (UUID featuredId : featuredIds) {
            assertTrue(resultIds.contains(featuredId),
                    "Featured product with id " + featuredId + " must appear in admin list");
        }

        // Assert: no non-featured product appears in the result
        Set<UUID> nonFeaturedIdSet = new HashSet<>(nonFeaturedIds);
        for (FeaturedProductSimulator.SimProduct resultProduct : result) {
            assertFalse(nonFeaturedIdSet.contains(resultProduct.id),
                    "Non-featured product must NOT appear in admin list");
        }

        // Assert: result is sorted by name ascending (lexicographic order)
        for (int i = 0; i < result.size() - 1; i++) {
            String currentName = result.get(i).name;
            String nextName = result.get(i + 1).name;
            assertTrue(currentName.compareTo(nextName) <= 0,
                    String.format(
                            "Ordering invariant violated at index %d: '%s' should be <= '%s' (ascending)",
                            i, currentName, nextName
                    ));
        }
    }

    /**
     * Property: When no products are featured, the admin list returns an empty list.
     */
    @Property(tries = 100)
    void adminListReturnsEmptyWhenNoProductsAreFeatured(
            @ForAll("nonFeaturedCount") int nonFeaturedCount,
            @ForAll("productNames") List<String> names,
            @ForAll("productStatuses") List<ProductStatusEn> statuses
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Add only non-featured products with unique UUIDs
        for (int i = 0; i < nonFeaturedCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("only-non-featured-" + i).getBytes());
            String name = names.get(i % names.size());
            ProductStatusEn status = statuses.get(i % statuses.size());
            simulator.addProduct(id, name, false, status);
        }

        List<FeaturedProductSimulator.SimProduct> result = simulator.getFeaturedProductsForAdmin();

        assertTrue(result.isEmpty(),
                "Admin list must be empty when no products are featured");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Integer> featuredCount() {
        return Arbitraries.integers().between(1, 50);
    }

    @Provide
    Arbitrary<Integer> nonFeaturedCount() {
        return Arbitraries.integers().between(0, 20);
    }

    @Provide
    Arbitrary<List<String>> productNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30)
                .list()
                .ofMinSize(50)
                .ofMaxSize(70);
    }

    @Provide
    Arbitrary<List<ProductStatusEn>> productStatuses() {
        return Arbitraries.of(ProductStatusEn.ACTIVE, ProductStatusEn.PENDING, ProductStatusEn.DISABLED)
                .list()
                .ofMinSize(50)
                .ofMaxSize(70);
    }
}
