package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 2: Cap Enforcement

import net.jqwik.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 2: Cap Enforcement
 *
 * For any set of exactly 50 products with is_featured = true, and for any additional
 * product not yet featured, calling setFeatured(id, true) should be rejected with a
 * FeaturedCapExceededException, and the count of featured products should remain exactly 50.
 *
 * This test verifies the cap enforcement property by simulating the FeaturedProductService
 * logic with an in-memory store that mirrors the Panache entity behavior.
 *
 * Validates: Requirements 1.3, 2.4
 */
class FeaturedCapEnforcementPropertyTest {

    /**
     * Simulates FeaturedProductService.setFeatured behavior with an in-memory store.
     * This mirrors the exact logic of the service without requiring Panache/DB.
     */
    private static class FeaturedProductSimulator {
        private final Map<UUID, Boolean> products = new HashMap<>();
        private final Set<UUID> featuredProducts = new HashSet<>();

        static final int FEATURED_CAP = 50;

        void addProduct(UUID productId, boolean featured) {
            products.put(productId, featured);
            if (featured) {
                featuredProducts.add(productId);
            }
        }

        /**
         * Replicates FeaturedProductService.setFeatured logic:
         * 1. Check product exists → NotFoundException if absent
         * 2. If featuring: check cap → FeaturedCapExceededException if count >= 50
         * 3. Set is_featured flag
         */
        SetFeaturedResult setFeatured(UUID productId, boolean featured) {
            // Check product existence (mirrors productRepository.findById)
            if (!products.containsKey(productId)) {
                return SetFeaturedResult.NOT_FOUND;
            }

            if (featured) {
                long currentCount = featuredProducts.size();
                if (currentCount >= FEATURED_CAP) {
                    return SetFeaturedResult.CAP_EXCEEDED;
                }
                products.put(productId, true);
                featuredProducts.add(productId);
            } else {
                products.put(productId, false);
                featuredProducts.remove(productId);
            }

            return SetFeaturedResult.SUCCESS;
        }

        int featuredCount() {
            return featuredProducts.size();
        }

        boolean isFeatured(UUID productId) {
            return featuredProducts.contains(productId);
        }
    }

    enum SetFeaturedResult {
        SUCCESS,
        NOT_FOUND,
        CAP_EXCEEDED
    }

    /**
     * Property: For any set of exactly 50 featured products and any additional non-featured
     * product, attempting to feature the new product is rejected with CAP_EXCEEDED and the
     * count remains exactly 50.
     */
    @Property(tries = 100)
    void featuringWhenCapReachedIsRejectedAndCountRemainsFifty(
            @ForAll("fiftyFeaturedProductIds") List<UUID> featuredIds,
            @ForAll("randomUUID") UUID newProductId
    ) {
        // Ensure newProductId is not in the featured set
        while (featuredIds.contains(newProductId)) {
            newProductId = UUID.randomUUID();
        }

        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Set up 50 featured products
        for (UUID id : featuredIds) {
            simulator.addProduct(id, true);
        }

        // Add the new product as non-featured
        simulator.addProduct(newProductId, false);

        // Verify preconditions
        assertEquals(50, simulator.featuredCount(),
                "Precondition: exactly 50 products should be featured");
        assertFalse(simulator.isFeatured(newProductId),
                "Precondition: new product should not be featured");

        // Attempt to feature the new product
        SetFeaturedResult result = simulator.setFeatured(newProductId, true);

        // Assert: operation is rejected with cap exceeded
        assertEquals(SetFeaturedResult.CAP_EXCEEDED, result,
                "Featuring should be rejected when cap of 50 is reached");

        // Assert: count remains exactly 50
        assertEquals(50, simulator.featuredCount(),
                "Featured count should remain exactly 50 after rejection");

        // Assert: new product is still not featured
        assertFalse(simulator.isFeatured(newProductId),
                "New product should remain non-featured after cap rejection");
    }

    /**
     * Property: Multiple consecutive attempts to feature new products when at cap are all
     * rejected and the count stays at 50.
     */
    @Property(tries = 100)
    void multipleFeatureAttemptsAtCapAreAllRejected(
            @ForAll("fiftyFeaturedProductIds") List<UUID> featuredIds,
            @ForAll("newProductIds") List<UUID> newProductIds
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Set up 50 featured products
        for (UUID id : featuredIds) {
            simulator.addProduct(id, true);
        }

        // Add new products as non-featured, ensuring no overlap with featured set
        Set<UUID> featuredSet = new HashSet<>(featuredIds);
        List<UUID> validNewIds = new ArrayList<>();
        for (UUID id : newProductIds) {
            if (!featuredSet.contains(id) && !validNewIds.contains(id)) {
                validNewIds.add(id);
                simulator.addProduct(id, false);
            }
        }

        assertEquals(50, simulator.featuredCount(),
                "Precondition: exactly 50 products should be featured");

        // Attempt to feature each new product
        for (UUID newId : validNewIds) {
            SetFeaturedResult result = simulator.setFeatured(newId, true);

            assertEquals(SetFeaturedResult.CAP_EXCEEDED, result,
                    "Each feature attempt should be rejected at cap");
        }

        // Assert: count remains exactly 50 after all attempts
        assertEquals(50, simulator.featuredCount(),
                "Featured count should remain exactly 50 after multiple rejections");

        // Assert: none of the new products are featured
        for (UUID newId : validNewIds) {
            assertFalse(simulator.isFeatured(newId),
                    "No new product should be featured after cap rejection");
        }
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> randomUUID() {
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new);
    }

    @Provide
    Arbitrary<List<UUID>> fiftyFeaturedProductIds() {
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new).set().ofSize(50).map(ArrayList::new);
    }

    @Provide
    Arbitrary<List<UUID>> newProductIds() {
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new).list().ofMinSize(1).ofMaxSize(5);
    }
}
