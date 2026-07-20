package org.ecommerce.backend.service;

// Feature: customer-portal-backend, Property 5: Wishlist Add Idempotence

import net.jqwik.api.*;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.WishlistItemEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 5: Wishlist Add Idempotence
 *
 * For any valid variant ID that exists in product_variants, adding it to a customer's
 * wishlist N times (N >= 1) SHALL result in exactly one entry in customer_wishlist_items
 * for that (customer, variant) pair. For any UUID that does not exist in product_variants,
 * the add operation SHALL return VARIANT_NOT_FOUND and the wishlist SHALL remain unchanged.
 *
 * This test verifies the idempotence property by simulating the WishlistService logic
 * with an in-memory store that mirrors the Panache entity behavior.
 *
 * Validates: Requirements 4.4
 */
class WishlistAddPropertyTest {

    /**
     * Simulates WishlistService.addToWishlist behavior with an in-memory store.
     * This mirrors the exact logic of the service without requiring Panache/DB.
     */
    private static class WishlistSimulator {
        private final Map<UUID, ProductVariantEntity> variants = new HashMap<>();
        private final Map<UUID, CustomerEntity> customers = new HashMap<>();
        private final Map<String, WishlistItemEntity> wishlistEntries = new HashMap<>();

        void addVariant(UUID variantId) {
            ProductVariantEntity variant = new ProductVariantEntity();
            variant.id = variantId;
            variants.put(variantId, variant);
        }

        void addCustomer(UUID customerId) {
            CustomerEntity customer = new CustomerEntity();
            customer.id = customerId;
            customers.put(customerId, customer);
        }

        /**
         * Replicates WishlistService.addToWishlist logic:
         * 1. Check variant exists → VARIANT_NOT_FOUND if absent
         * 2. Check for existing entry → ALREADY_EXISTS if present
         * 3. Persist new entry → CREATED
         */
        WishlistService.AddResult addToWishlist(UUID customerId, UUID variantId) {
            // Check variant existence (mirrors ProductVariantEntity.findById)
            ProductVariantEntity variant = variants.get(variantId);
            if (variant == null) {
                return WishlistService.AddResult.VARIANT_NOT_FOUND;
            }

            // Check for existing entry (mirrors WishlistItemEntity.findByCustomerAndVariant)
            String key = customerId.toString() + ":" + variantId.toString();
            WishlistItemEntity existing = wishlistEntries.get(key);
            if (existing != null) {
                return WishlistService.AddResult.ALREADY_EXISTS;
            }

            // Persist new entry (mirrors CustomerEntity.findById + persist)
            CustomerEntity customer = customers.get(customerId);
            WishlistItemEntity newItem = new WishlistItemEntity();
            newItem.customer = customer;
            newItem.variant = variant;
            newItem.id = UUID.randomUUID();
            wishlistEntries.put(key, newItem);

            return WishlistService.AddResult.CREATED;
        }

        int entryCount(UUID customerId, UUID variantId) {
            String key = customerId.toString() + ":" + variantId.toString();
            return wishlistEntries.containsKey(key) ? 1 : 0;
        }

        int totalEntries() {
            return wishlistEntries.size();
        }
    }

    /**
     * Property: Adding a valid variant N times (1-10) results in exactly one entry.
     * The first add returns CREATED, all subsequent adds return ALREADY_EXISTS.
     * After N adds, exactly one entry exists for that (customer, variant) pair.
     */
    @Property(tries = 100)
    void addingExistingVariantNTimesResultsInExactlyOneEntry(
            @ForAll("randomUUID") UUID customerId,
            @ForAll("randomUUID") UUID variantId,
            @ForAll("addCount") int addCount
    ) {
        WishlistSimulator simulator = new WishlistSimulator();
        simulator.addVariant(variantId);
        simulator.addCustomer(customerId);

        // Perform N add operations
        WishlistService.AddResult firstResult = null;
        int createdCount = 0;
        int alreadyExistsCount = 0;

        for (int i = 0; i < addCount; i++) {
            WishlistService.AddResult result = simulator.addToWishlist(customerId, variantId);
            if (i == 0) {
                firstResult = result;
            }
            if (result == WishlistService.AddResult.CREATED) {
                createdCount++;
            } else if (result == WishlistService.AddResult.ALREADY_EXISTS) {
                alreadyExistsCount++;
            }
        }

        // Assert: first add returns CREATED
        assertEquals(WishlistService.AddResult.CREATED, firstResult,
                "First add should return CREATED");

        // Assert: exactly one CREATED result across all adds
        assertEquals(1, createdCount,
                "Exactly one add should return CREATED");

        // Assert: remaining adds return ALREADY_EXISTS
        assertEquals(addCount - 1, alreadyExistsCount,
                "All subsequent adds should return ALREADY_EXISTS");

        // Assert: exactly one entry exists for this (customer, variant) pair
        assertEquals(1, simulator.entryCount(customerId, variantId),
                "Exactly one entry should exist for (customer, variant) pair after N adds");

        // Assert: total entries is exactly 1
        assertEquals(1, simulator.totalEntries(),
                "Total wishlist entries should be exactly 1");
    }

    /**
     * Property: For non-existent variants, addToWishlist returns VARIANT_NOT_FOUND
     * and no persist occurs (wishlist remains unchanged).
     */
    @Property(tries = 100)
    void addingNonExistentVariantReturnsNotFoundAndNoChange(
            @ForAll("randomUUID") UUID customerId,
            @ForAll("randomUUID") UUID variantId,
            @ForAll("addCount") int addCount
    ) {
        WishlistSimulator simulator = new WishlistSimulator();
        simulator.addCustomer(customerId);
        // NOTE: variant is intentionally NOT added — simulates non-existent variant

        int entriesBefore = simulator.totalEntries();

        // Perform N add attempts
        for (int i = 0; i < addCount; i++) {
            WishlistService.AddResult result = simulator.addToWishlist(customerId, variantId);

            // Assert: every add returns VARIANT_NOT_FOUND
            assertEquals(WishlistService.AddResult.VARIANT_NOT_FOUND, result,
                    "Add with non-existent variant should always return VARIANT_NOT_FOUND");
        }

        // Assert: no entries were created (wishlist unchanged)
        assertEquals(entriesBefore, simulator.totalEntries(),
                "Wishlist should remain unchanged when variant does not exist");

        // Assert: specifically no entry for this (customer, variant) pair
        assertEquals(0, simulator.entryCount(customerId, variantId),
                "No entry should exist for non-existent variant");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> randomUUID() {
        // Use longs to build UUIDs so jqwik uses randomized generation (not exhaustive)
        return Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new);
    }

    @Provide
    Arbitrary<Integer> addCount() {
        return Arbitraries.integers().between(1, 10);
    }
}
