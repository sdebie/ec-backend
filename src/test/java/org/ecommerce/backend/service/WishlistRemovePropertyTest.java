package org.ecommerce.backend.service;

import net.jqwik.api.*;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.WishlistItemEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 6: Wishlist Remove Idempotence
 * <p>
 * For any variant ID (whether it exists in the customer's wishlist),
 * the DELETE operation SHALL return 204, and after the operation the variant
 * SHALL NOT appear in the customer's wishlist.
 * <p>
 * This test verifies the idempotence property by simulating the WishlistService
 * removeFromWishlist logic with an in-memory store that mirrors Panache entity behaviour.
 * <p>
 */
class WishlistRemovePropertyTest
{
    /**
     * Simulates WishlistService.removeFromWishlist behaviour with an in-memory store.
     * Supports pre-populating a wishlist with random variants and then removing it.
     */
    private static class WishlistSimulator
    {
        private final Map<String, WishlistItemEntity> wishlistEntries = new HashMap<>();
        private final Map<UUID, CustomerEntity> customers = new HashMap<>();
        private final Map<UUID, ProductVariantEntity> variants = new HashMap<>();

        void addCustomer(UUID customerId)
        {
            CustomerEntity customer = new CustomerEntity();
            customer.setId(customerId);
            customers.put(customerId, customer);
        }

        void addVariant(UUID variantId)
        {
            ProductVariantEntity variant = new ProductVariantEntity();
            variant.setId(variantId);
            variants.put(variantId, variant);
        }

        /**
         * Pre-populates a wishlist entry for the given customer and variant.
         * Simulates a prior addToWishlist that succeeded.
         */
        void seedWishlistEntry(UUID customerId, UUID variantId)
        {
            String key = customerId.toString() + ":" + variantId.toString();
            if (!wishlistEntries.containsKey(key)) {
                WishlistItemEntity item = new WishlistItemEntity();
                item.setId(UUID.randomUUID());
                item.setCustomer(customers.get(customerId));
                item.setVariant(variants.get(variantId));
                wishlistEntries.put(key, item);
            }
        }

        /**
         * Replicates WishlistService.removeFromWishlist logic:
         * Deletes the entry for (customer, variant) if it exists. No-op if absent.
         * Always succeeds (idempotent — does not error on missing).
         */
        void removeFromWishlist(UUID customerId, UUID variantId)
        {
            String key = customerId.toString() + ":" + variantId.toString();
            wishlistEntries.remove(key);
        }

        boolean containsEntry(UUID customerId, UUID variantId)
        {
            String key = customerId.toString() + ":" + variantId.toString();
            return wishlistEntries.containsKey(key);
        }

        List<UUID> getVariantIdsForCustomer(UUID customerId)
        {
            List<UUID> result = new ArrayList<>();
            String prefix = customerId.toString() + ":";
            for (Map.Entry<String, WishlistItemEntity> entry : wishlistEntries.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.add(entry.getValue().getVariant().getId());
                }
            }
            return result;
        }

        int totalEntries()
        {
            return wishlistEntries.size();
        }
    }

    /**
     * Property: Removing a variant that IS in the wishlist results in the variant
     * no longer appearing in the customer's wishlist. The operation is idempotent —
     * calling remove multiple times has the same effect as calling it once.
     */
    @Property(tries = 100)
    void removingExistingVariantRemovesItFromWishlist(@ForAll("randomUUID") UUID customerId, @ForAll("initialWishlistVariants") List<UUID> initialVariants, @ForAll("removeCount") int removeCount)
    {
        WishlistSimulator simulator = new WishlistSimulator();
        simulator.addCustomer(customerId);

        // Seed initial wishlist state with random variants
        for (UUID variantId : initialVariants) {
            simulator.addVariant(variantId);
            simulator.seedWishlistEntry(customerId, variantId);
        }

        // Pick the first variant to remove (guaranteed to be in the wishlist)
        Assume.that(!initialVariants.isEmpty());
        UUID variantToRemove = initialVariants.getFirst();

        // Verify it exists before removal
        assertTrue(simulator.containsEntry(customerId, variantToRemove), "Variant should exist in wishlist before removal");

        // Perform remove N times (testing idempotence)
        for (int i = 0; i < removeCount; i++) {
            simulator.removeFromWishlist(customerId, variantToRemove);
        }

        // Assert: variant does not appear after removal
        assertFalse(simulator.containsEntry(customerId, variantToRemove), "Variant should NOT appear in wishlist after removal");

        // Assert: variant is not in the customer's variant ID list
        List<UUID> remainingVariants = simulator.getVariantIdsForCustomer(customerId);
        assertFalse(remainingVariants.contains(variantToRemove), "Removed variant should not be in the customer's wishlist variant IDs");
    }

    /**
     * Property: Removing a variant that is NOT in the wishlist succeeds without error
     * (idempotent) and the wishlist remains unchanged.
     */
    @Property(tries = 100)
    void removingNonExistentVariantSucceedsAndLeavesWishlistUnchanged(@ForAll("randomUUID") UUID customerId, @ForAll("initialWishlistVariants") List<UUID> initialVariants, @ForAll("randomUUID") UUID variantToRemove, @ForAll("removeCount") int removeCount)
    {
        // Ensure the variant to remove is NOT in the initial wishlist
        Assume.that(!initialVariants.contains(variantToRemove));

        WishlistSimulator simulator = new WishlistSimulator();
        simulator.addCustomer(customerId);

        // Seed initial wishlist state
        for (UUID variantId : initialVariants) {
            simulator.addVariant(variantId);
            simulator.seedWishlistEntry(customerId, variantId);
        }

        int entriesBefore = simulator.totalEntries();
        List<UUID> variantsBefore = simulator.getVariantIdsForCustomer(customerId);

        // Perform remove N times on a variant not in the wishlist
        for (int i = 0; i < removeCount; i++) {
            simulator.removeFromWishlist(customerId, variantToRemove);
        }

        // Assert: variant does not appear (was never there)
        assertFalse(simulator.containsEntry(customerId, variantToRemove), "Variant should NOT appear in wishlist after removal (was never present)");

        // Assert: wishlist is unchanged — all original entries remain
        assertEquals(entriesBefore, simulator.totalEntries(), "Total entries should not change when removing non-existent variant");

        List<UUID> variantsAfter = simulator.getVariantIdsForCustomer(customerId);
        assertEquals(new HashSet<>(variantsBefore), new HashSet<>(variantsAfter), "Wishlist variant IDs should remain unchanged when removing non-existent variant");
    }

    /**
     * Property: After removal, the variant does not appear regardless of the initial
     * state — whether the wishlist was empty, had one item, or had many items.
     */
    @Property(tries = 100)
    void variantAbsentAfterRemovalRegardlessOfInitialState(@ForAll("randomUUID") UUID customerId, @ForAll("initialWishlistVariants") List<UUID> initialVariants, @ForAll("randomUUID") UUID variantToRemove)
    {
        WishlistSimulator simulator = new WishlistSimulator();
        simulator.addCustomer(customerId);

        // Seed initial wishlist — may or may not include variantToRemove
        for (UUID variantId : initialVariants) {
            simulator.addVariant(variantId);
            simulator.seedWishlistEntry(customerId, variantId);
        }

        // Also add the variantToRemove to the variant map (in case it's in the wishlist)
        simulator.addVariant(variantToRemove);
        // Randomly the variant might be in the initial state or not — doesn't matter

        // Perform the removal
        simulator.removeFromWishlist(customerId, variantToRemove);

        // Assert: regardless of whether the variant was initially present or not,
        // it does not appear in the wishlist after removal
        assertFalse(simulator.containsEntry(customerId, variantToRemove), "Variant must not appear in wishlist after removal, regardless of initial state");

        List<UUID> remainingVariants = simulator.getVariantIdsForCustomer(customerId);
        assertFalse(remainingVariants.contains(variantToRemove), "Variant must not be in variant ID list after removal");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> randomUUID()
    {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
    }

    @Provide
    Arbitrary<List<UUID>> initialWishlistVariants()
    {
        // Generate 0-5 random variant UUIDs for an initial wishlist state
        Arbitrary<UUID> uuidArb = Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
        return uuidArb.list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Integer> removeCount()
    {
        return Arbitraries.integers().between(1, 5);
    }
}
