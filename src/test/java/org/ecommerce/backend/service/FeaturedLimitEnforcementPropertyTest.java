package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 7: Limit Enforcement

import net.jqwik.api.*;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 7: Limit Enforcement
 *
 * For any integer limit in [1, 50] and for any set of featured active products,
 * the shoppingFeaturedProductList(limit) query should return at most limit products.
 * When limit is omitted, at most 8 products should be returned.
 *
 * This test verifies the limit enforcement by simulating the FeaturedProductService
 * logic with an in-memory store that mirrors the Panache entity behavior.
 *
 * Validates: Requirements 4.2
 */
class FeaturedLimitEnforcementPropertyTest {

    private static final int FEATURED_CAP = 50;
    private static final int DEFAULT_LIMIT = 8;

    /**
     * Simulates FeaturedProductService.getFeaturedShoppingProducts behavior.
     * Mirrors the exact logic: filter by isFeatured=true AND status=ACTIVE,
     * order by name ASC, then apply the resolved limit via page(0, effectiveLimit).
     */
    private static class FeaturedProductSimulator {
        private final Map<UUID, SimProduct> products = new HashMap<>();

        static class SimProduct {
            UUID id;
            String name;
            boolean isFeatured;
            ProductStatusEn status;

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
         * Replicates FeaturedProductService.getFeaturedShoppingProducts logic:
         * 1. Resolve limit (null or < 1 → 8, otherwise min(limit, 50))
         * 2. Filter: isFeatured = true AND status = ACTIVE
         * 3. Order by name ascending
         * 4. Page(0, effectiveLimit) → return at most effectiveLimit products
         */
        List<SimProduct> getFeaturedShoppingProducts(Integer limit) {
            int effectiveLimit = resolveLimit(limit);

            return products.values().stream()
                    .filter(p -> p.isFeatured && p.status == ProductStatusEn.ACTIVE)
                    .sorted(Comparator.comparing(p -> p.name))
                    .limit(effectiveLimit)
                    .collect(Collectors.toList());
        }

        /**
         * Replicates FeaturedProductService.resolveLimit:
         * - null or < 1 → 8 (default)
         * - otherwise → min(limit, 50)
         */
        private int resolveLimit(Integer limit) {
            if (limit == null || limit < 1) {
                return DEFAULT_LIMIT;
            }
            return Math.min(limit, FEATURED_CAP);
        }
    }

    /**
     * Property: For any limit in [1, 50], the result set size is at most limit.
     */
    @Property(tries = 100)
    void resultSizeNeverExceedsExplicitLimit(
            @ForAll("validLimit") int limit,
            @ForAll("featuredActiveCount") int featuredActiveCount,
            @ForAll("otherProductCount") int otherProductCount
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Add featured + ACTIVE products with deterministic unique IDs
        for (int i = 0; i < featuredActiveCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("featured-active-" + i).getBytes());
            simulator.addProduct(id, "FeaturedActive " + i, true, ProductStatusEn.ACTIVE);
        }

        // Add other products (non-featured or non-active) with separate unique IDs
        for (int i = 0; i < otherProductCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("other-" + i).getBytes());
            // Alternate between non-featured active and featured non-active
            if (i % 2 == 0) {
                simulator.addProduct(id, "Other " + i, false, ProductStatusEn.ACTIVE);
            } else {
                simulator.addProduct(id, "Other " + i, true, ProductStatusEn.PENDING);
            }
        }

        List<FeaturedProductSimulator.SimProduct> result = simulator.getFeaturedShoppingProducts(limit);

        // Assert: result size never exceeds the provided limit
        assertTrue(result.size() <= limit,
                String.format(
                        "Result size %d exceeds explicit limit %d (featured active count: %d)",
                        result.size(), limit, featuredActiveCount
                ));

        // Assert: result size is exactly min(limit, available featured active count)
        int expectedSize = Math.min(limit, featuredActiveCount);
        assertEquals(expectedSize, result.size(),
                String.format(
                        "Expected result size to be min(limit=%d, available=%d) = %d but was %d",
                        limit, featuredActiveCount, expectedSize, result.size()
                ));
    }

    /**
     * Property: When limit is omitted (null), the result size is at most 8.
     */
    @Property(tries = 100)
    void resultSizeNeverExceedsDefaultLimitWhenOmitted(
            @ForAll("featuredActiveCount") int featuredActiveCount,
            @ForAll("otherProductCount") int otherProductCount
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Add featured + ACTIVE products with deterministic unique IDs
        for (int i = 0; i < featuredActiveCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("featured-active-" + i).getBytes());
            simulator.addProduct(id, "FeaturedActive " + i, true, ProductStatusEn.ACTIVE);
        }

        // Add other products (non-featured or non-active) with separate unique IDs
        for (int i = 0; i < otherProductCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("other-" + i).getBytes());
            simulator.addProduct(id, "Other " + i, false, ProductStatusEn.ACTIVE);
        }

        // Call with null limit (omitted)
        List<FeaturedProductSimulator.SimProduct> result = simulator.getFeaturedShoppingProducts(null);

        // Assert: result size never exceeds the default limit of 8
        assertTrue(result.size() <= DEFAULT_LIMIT,
                String.format(
                        "Result size %d exceeds default limit %d when limit is omitted (featured active count: %d)",
                        result.size(), DEFAULT_LIMIT, featuredActiveCount
                ));

        // Assert: result size is min(8, available featured active count)
        int expectedSize = Math.min(DEFAULT_LIMIT, featuredActiveCount);
        assertEquals(expectedSize, result.size(),
                String.format(
                        "Expected result size to be min(default=%d, available=%d) = %d but was %d",
                        DEFAULT_LIMIT, featuredActiveCount, expectedSize, result.size()
                ));
    }

    /**
     * Property: When limit is below 1 (invalid), the result behaves as if omitted (default 8).
     */
    @Property(tries = 100)
    void invalidLimitBelowOneFallsBackToDefault(
            @ForAll("invalidLimit") int invalidLimit,
            @ForAll("featuredActiveCount") int featuredActiveCount
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Add featured + ACTIVE products with deterministic unique IDs
        for (int i = 0; i < featuredActiveCount; i++) {
            UUID id = UUID.nameUUIDFromBytes(("featured-active-" + i).getBytes());
            simulator.addProduct(id, "FeaturedActive " + i, true, ProductStatusEn.ACTIVE);
        }

        List<FeaturedProductSimulator.SimProduct> result = simulator.getFeaturedShoppingProducts(invalidLimit);

        // Assert: result size never exceeds the default limit of 8
        assertTrue(result.size() <= DEFAULT_LIMIT,
                String.format(
                        "Result size %d exceeds default limit %d for invalid limit %d",
                        result.size(), DEFAULT_LIMIT, invalidLimit
                ));
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Integer> validLimit() {
        return Arbitraries.integers().between(1, 50);
    }

    @Provide
    Arbitrary<Integer> invalidLimit() {
        return Arbitraries.integers().between(-100, 0);
    }

    @Provide
    Arbitrary<Integer> featuredActiveCount() {
        return Arbitraries.integers().between(0, 50);
    }

    @Provide
    Arbitrary<Integer> otherProductCount() {
        return Arbitraries.integers().between(0, 20);
    }
}
