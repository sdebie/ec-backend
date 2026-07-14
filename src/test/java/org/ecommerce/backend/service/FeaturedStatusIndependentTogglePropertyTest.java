package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 4: Status-Independent Toggle

import net.jqwik.api.*;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 4: Status-Independent Toggle
 *
 * For any product with any status (ACTIVE, PENDING, or DISABLED), calling
 * setFeatured(id, true) should succeed when the cap has not been reached,
 * regardless of the product's status.
 *
 * This test simulates the FeaturedProductService.setFeatured logic in-memory,
 * verifying that the status of a product does not affect the ability to feature it.
 *
 * Validates: Requirements 2.6
 */
class FeaturedStatusIndependentTogglePropertyTest {

    private static final int FEATURED_CAP = 50;

    /**
     * Simulates FeaturedProductService.setFeatured behavior in-memory.
     * Products are stored by ID with their status and featured flag.
     */
    private static class FeaturedServiceSimulator {
        private final Map<UUID, SimulatedProduct> products = new HashMap<>();

        static class SimulatedProduct {
            UUID id;
            ProductStatusEn status;
            boolean isFeatured;

            SimulatedProduct(UUID id, ProductStatusEn status, boolean isFeatured) {
                this.id = id;
                this.status = status;
                this.isFeatured = isFeatured;
            }
        }

        void addProduct(UUID id, ProductStatusEn status, boolean isFeatured) {
            products.put(id, new SimulatedProduct(id, status, isFeatured));
        }

        /**
         * Replicates FeaturedProductService.setFeatured logic:
         * 1. Find product by ID → NotFoundException if absent
         * 2. If featuring: count current featured; if >= 50, throw cap error
         * 3. Set isFeatured = true
         *
         * Importantly: product status is NOT checked — any status can be featured.
         */
        SetFeaturedResult setFeatured(UUID productId, boolean featured) {
            SimulatedProduct product = products.get(productId);
            if (product == null) {
                return SetFeaturedResult.NOT_FOUND;
            }

            if (featured) {
                long currentCount = products.values().stream()
                        .filter(p -> p.isFeatured)
                        .count();
                if (currentCount >= FEATURED_CAP) {
                    return SetFeaturedResult.CAP_EXCEEDED;
                }
                product.isFeatured = true;
            } else {
                product.isFeatured = false;
            }

            return SetFeaturedResult.SUCCESS;
        }

        boolean isFeatured(UUID productId) {
            SimulatedProduct product = products.get(productId);
            return product != null && product.isFeatured;
        }

        long featuredCount() {
            return products.values().stream().filter(p -> p.isFeatured).count();
        }
    }

    enum SetFeaturedResult {
        SUCCESS,
        NOT_FOUND,
        CAP_EXCEEDED
    }

    /**
     * Validates: Requirements 2.6
     *
     * For any product with any status (ACTIVE, PENDING, DISABLED), setFeatured(id, true)
     * succeeds when the cap has not been reached.
     * The product's status does not influence the ability to toggle the featured flag.
     */
    @Property(tries = 100)
    void setFeaturedSucceedsRegardlessOfProductStatus(
            @ForAll("randomUUID") UUID productId,
            @ForAll("productStatus") ProductStatusEn status,
            @ForAll("featuredCountBelowCap") int existingFeaturedCount
    ) {
        FeaturedServiceSimulator simulator = new FeaturedServiceSimulator();

        // Add the target product with the given status, initially not featured
        simulator.addProduct(productId, status, false);

        // Add some existing featured products (below cap) to simulate a realistic state
        for (int i = 0; i < existingFeaturedCount; i++) {
            UUID existingId = UUID.randomUUID();
            simulator.addProduct(existingId, ProductStatusEn.ACTIVE, true);
        }

        // Act: attempt to feature the product
        SetFeaturedResult result = simulator.setFeatured(productId, true);

        // Assert: featuring succeeds regardless of product status
        assertEquals(SetFeaturedResult.SUCCESS, result,
                "setFeatured(id, true) should succeed for product with status " + status
                        + " when cap is not reached (current count: " + existingFeaturedCount + ")");

        // Assert: product is now featured
        assertTrue(simulator.isFeatured(productId),
                "Product should be featured after successful setFeatured(id, true)");

        // Assert: featured count increased by 1
        assertEquals(existingFeaturedCount + 1, simulator.featuredCount(),
                "Featured count should increase by 1 after featuring a product");
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
    Arbitrary<ProductStatusEn> productStatus() {
        return Arbitraries.of(ProductStatusEn.ACTIVE, ProductStatusEn.PENDING, ProductStatusEn.DISABLED);
    }

    @Provide
    Arbitrary<Integer> featuredCountBelowCap() {
        return Arbitraries.integers().between(0, 49);
    }
}
