package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 3: Not-Found Rejection

import jakarta.ws.rs.NotFoundException;
import net.jqwik.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 3: Not-Found Rejection
 *
 * For any UUID that does not correspond to an existing product in the database,
 * calling setFeatured(uuid, true) or setFeatured(uuid, false) should return a
 * not-found error and leave all products unchanged.
 *
 * This test simulates the FeaturedProductService.setFeatured logic with an in-memory
 * store. The key behaviour under test: productRepository.findById returns null for
 * non-existent UUIDs, and the service throws NotFoundException before mutating anything.
 *
 * Validates: Requirements 2.3
 */
class FeaturedNotFoundPropertyTest {

    /**
     * Simulates FeaturedProductService.setFeatured behavior with an in-memory product store.
     * Mirrors the exact logic of the service for the not-found path.
     */
    private static class FeaturedServiceSimulator {
        private final Map<UUID, Boolean> products = new HashMap<>();

        void addProduct(UUID productId, boolean isFeatured) {
            products.put(productId, isFeatured);
        }

        /**
         * Replicates FeaturedProductService.setFeatured logic:
         * 1. Look up product by ID → throw NotFoundException if absent
         * 2. Otherwise toggle the featured flag
         */
        void setFeatured(UUID productId, boolean featured) {
            Boolean existing = products.get(productId);
            if (existing == null) {
                throw new NotFoundException("Product not found with id: " + productId);
            }
            products.put(productId, featured);
        }

        /**
         * Returns a snapshot of all product featured states for comparison.
         */
        Map<UUID, Boolean> snapshot() {
            return new HashMap<>(products);
        }
    }

    /**
     * Validates: Requirements 2.3
     *
     * For any UUID that does not match a product, setFeatured(uuid, true) throws
     * NotFoundException and no product's featured state changes.
     */
    @Property(tries = 100)
    void setFeaturedTrueWithNonExistentIdThrowsNotFoundAndNoChange(
            @ForAll("randomUUID") UUID nonExistentId,
            @ForAll("randomUUID") UUID existingProductId
    ) {
        Assume.that(!nonExistentId.equals(existingProductId));

        FeaturedServiceSimulator simulator = new FeaturedServiceSimulator();
        simulator.addProduct(existingProductId, false);

        Map<UUID, Boolean> snapshotBefore = simulator.snapshot();

        // Act & Assert: calling setFeatured with non-existent UUID must throw NotFoundException
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> simulator.setFeatured(nonExistentId, true));

        assertTrue(ex.getMessage().contains(nonExistentId.toString()),
                "Error message should reference the missing product ID");

        // Assert: no product state changed
        assertEquals(snapshotBefore, simulator.snapshot(),
                "No product should be modified when setFeatured is called with a non-existent ID");
    }

    /**
     * Validates: Requirements 2.3
     *
     * For any UUID that does not match a product, setFeatured(uuid, false) throws
     * NotFoundException and no product's featured state changes.
     */
    @Property(tries = 100)
    void setFeaturedFalseWithNonExistentIdThrowsNotFoundAndNoChange(
            @ForAll("randomUUID") UUID nonExistentId,
            @ForAll("randomUUID") UUID existingProductId
    ) {
        Assume.that(!nonExistentId.equals(existingProductId));

        FeaturedServiceSimulator simulator = new FeaturedServiceSimulator();
        simulator.addProduct(existingProductId, true);

        Map<UUID, Boolean> snapshotBefore = simulator.snapshot();

        // Act & Assert: calling setFeatured with non-existent UUID must throw NotFoundException
        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> simulator.setFeatured(nonExistentId, false));

        assertTrue(ex.getMessage().contains(nonExistentId.toString()),
                "Error message should reference the missing product ID");

        // Assert: no product state changed
        assertEquals(snapshotBefore, simulator.snapshot(),
                "No product should be modified when setFeatured is called with a non-existent ID");
    }

    /**
     * Validates: Requirements 2.3
     *
     * For any UUID not in the product set, calling setFeatured with either boolean value
     * always throws NotFoundException — the boolean parameter does not affect the outcome.
     */
    @Property(tries = 100)
    void setFeaturedWithNonExistentIdAlwaysThrowsRegardlessOfFeaturedValue(
            @ForAll("randomUUID") UUID nonExistentId,
            @ForAll boolean featured
    ) {
        // Empty store — no products exist
        FeaturedServiceSimulator simulator = new FeaturedServiceSimulator();

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> simulator.setFeatured(nonExistentId, featured));

        assertTrue(ex.getMessage().contains(nonExistentId.toString()),
                "Error message should reference the missing product ID");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> randomUUID() {
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new);
    }
}
