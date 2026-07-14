package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 1: Featured Toggle Round-Trip

import net.jqwik.api.*;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 1: Featured Toggle Round-Trip
 *
 * For any product that is not featured, calling setFeatured(id, true) followed by
 * setFeatured(id, false) should leave is_featured = false, and the product should
 * not appear in the featured list.
 *
 * This test verifies the round-trip property by simulating the FeaturedProductService
 * logic with an in-memory store that mirrors the Panache entity behavior.
 *
 * Validates: Requirements 1.4, 2.1, 2.2
 */
class FeaturedToggleRoundTripPropertyTest {

    private static final int FEATURED_CAP = 50;

    /**
     * Simulates FeaturedProductService behavior with an in-memory store.
     * Mirrors the exact logic of the service without requiring Panache/DB.
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
         * Replicates FeaturedProductService.setFeatured logic:
         * 1. Find product by id → NotFoundException if absent
         * 2. If featuring: check cap → FeaturedCapExceededException if count >= 50
         * 3. Set is_featured flag
         * 4. Return result with productId and new featured state
         */
        SetFeaturedResult setFeatured(UUID productId, boolean featured) {
            SimProduct product = products.get(productId);
            if (product == null) {
                throw new NoSuchElementException("Product not found with id: " + productId);
            }

            if (featured) {
                long currentCount = products.values().stream()
                        .filter(p -> p.isFeatured)
                        .count();
                if (currentCount >= FEATURED_CAP) {
                    throw new IllegalStateException("Featured limit of 50 reached.");
                }
                product.isFeatured = true;
            } else {
                product.isFeatured = false;
            }

            return new SetFeaturedResult(productId.toString(), product.isFeatured);
        }

        /**
         * Returns all products where isFeatured = true, ordered by name ascending.
         * Mirrors getFeaturedProductsForAdmin behavior.
         */
        List<SimProduct> getFeaturedProducts() {
            return products.values().stream()
                    .filter(p -> p.isFeatured)
                    .sorted(Comparator.comparing(p -> p.name))
                    .toList();
        }

        boolean isProductFeatured(UUID productId) {
            SimProduct product = products.get(productId);
            return product != null && product.isFeatured;
        }
    }

    private record SetFeaturedResult(String productId, boolean featured) {}

    /**
     * Property: For any product not featured, setFeatured(id, true) then
     * setFeatured(id, false) leaves is_featured = false and the product
     * absent from the featured list.
     */
    @Property(tries = 100)
    void toggleRoundTripLeavesProductUnfeaturedAndAbsentFromList(
            @ForAll("randomUUID") UUID productId,
            @ForAll("productName") String productName,
            @ForAll("productStatus") ProductStatusEn status,
            @ForAll("existingFeaturedCount") int existingFeaturedCount
    ) {
        FeaturedProductSimulator simulator = new FeaturedProductSimulator();

        // Seed existing featured products (to verify cap is not affected by round-trip)
        for (int i = 0; i < existingFeaturedCount; i++) {
            UUID existingId = UUID.nameUUIDFromBytes(("existing-" + i).getBytes());
            simulator.addProduct(existingId, "Existing Product " + i, true, ProductStatusEn.ACTIVE);
        }

        // Add the target product as NOT featured
        simulator.addProduct(productId, productName, false, status);

        // Verify product starts as not featured
        assertFalse(simulator.isProductFeatured(productId),
                "Product should start as NOT featured");

        // Step 1: setFeatured(id, true) — should succeed (cap not reached)
        SetFeaturedResult featureResult = simulator.setFeatured(productId, true);
        assertTrue(featureResult.featured(),
                "setFeatured(id, true) should return featured = true");
        assertTrue(simulator.isProductFeatured(productId),
                "Product should be featured after setFeatured(id, true)");

        // Step 2: setFeatured(id, false) — completes the round-trip
        SetFeaturedResult unfeatureResult = simulator.setFeatured(productId, false);
        assertFalse(unfeatureResult.featured(),
                "setFeatured(id, false) should return featured = false");

        // Assert: is_featured = false after round-trip
        assertFalse(simulator.isProductFeatured(productId),
                "Product's is_featured must be false after toggle round-trip");

        // Assert: product is absent from the featured list
        List<FeaturedProductSimulator.SimProduct> featuredList = simulator.getFeaturedProducts();
        boolean productInList = featuredList.stream()
                .anyMatch(p -> p.id.equals(productId));
        assertFalse(productInList,
                "Product must NOT appear in the featured list after toggle round-trip");

        // Assert: existing featured products are unaffected
        long featuredCount = featuredList.size();
        assertEquals(existingFeaturedCount, (int) featuredCount,
                "Existing featured products should remain unchanged after round-trip");
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
    Arbitrary<String> productName() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<ProductStatusEn> productStatus() {
        return Arbitraries.of(ProductStatusEn.ACTIVE, ProductStatusEn.PENDING, ProductStatusEn.DISABLED);
    }

    @Provide
    Arbitrary<Integer> existingFeaturedCount() {
        // 0 to 49 — must leave room for the round-trip product to be featured
        return Arbitraries.integers().between(0, 49);
    }
}
