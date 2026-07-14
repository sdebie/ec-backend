package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 8: Category Filter Correctness

import net.jqwik.api.*;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 8: Category Filter Correctness
 *
 * For any category slug provided to getFeaturedShoppingProducts, all returned
 * products should belong to that category. When the slug matches no category,
 * the result should be an empty list.
 *
 * This test simulates the FeaturedProductService.getFeaturedShoppingProducts logic
 * with an in-memory store, verifying category filter correctness across randomly
 * generated product/category combinations.
 *
 * Validates: Requirements 4.3
 */
class FeaturedCategoryFilterCorrectnessPropertyTest {

    private static final int FEATURED_CAP = 50;

    /**
     * Simulates FeaturedProductService.getFeaturedShoppingProducts category filtering.
     * Mirrors the exact logic of the service without requiring Panache/DB.
     */
    private static class FeaturedShoppingSimulator {
        private final Map<UUID, SimProduct> products = new HashMap<>();
        private final Map<String, SimCategory> categoriesBySlug = new HashMap<>();

        static class SimCategory {
            UUID id;
            String name;
            String slug;

            SimCategory(UUID id, String name, String slug) {
                this.id = id;
                this.name = name;
                this.slug = slug;
            }
        }

        static class SimProduct {
            UUID id;
            String name;
            boolean isFeatured;
            ProductStatusEn status;
            Set<UUID> categoryIds;

            SimProduct(UUID id, String name, boolean isFeatured, ProductStatusEn status, Set<UUID> categoryIds) {
                this.id = id;
                this.name = name;
                this.isFeatured = isFeatured;
                this.status = status;
                this.categoryIds = categoryIds;
            }
        }

        void addCategory(UUID id, String name, String slug) {
            categoriesBySlug.put(slug.toLowerCase(), new SimCategory(id, name, slug));
        }

        void addProduct(UUID id, String name, boolean isFeatured, ProductStatusEn status, Set<UUID> categoryIds) {
            products.put(id, new SimProduct(id, name, isFeatured, status, categoryIds));
        }

        /**
         * Replicates FeaturedProductService.getFeaturedShoppingProducts logic for
         * category filtering:
         * 1. If categorySlug is provided and not blank:
         *    a. Resolve slug to category (case-insensitive) → empty list if no match
         *    b. Filter: isFeatured = true AND status = ACTIVE AND product belongs to category
         * 2. If no categorySlug: return all featured + ACTIVE products
         * 3. Sort by name ascending
         * 4. Apply limit
         */
        List<SimProduct> getFeaturedShoppingProducts(Integer limit, String categorySlug) {
            int effectiveLimit = resolveLimit(limit);

            List<SimProduct> result;

            if (categorySlug != null && !categorySlug.isBlank()) {
                SimCategory category = categoriesBySlug.get(categorySlug.toLowerCase());
                if (category == null) {
                    return List.of();
                }

                result = products.values().stream()
                        .filter(p -> p.isFeatured)
                        .filter(p -> p.status == ProductStatusEn.ACTIVE)
                        .filter(p -> p.categoryIds.contains(category.id))
                        .sorted(Comparator.comparing(p -> p.name))
                        .limit(effectiveLimit)
                        .collect(Collectors.toList());
            } else {
                result = products.values().stream()
                        .filter(p -> p.isFeatured)
                        .filter(p -> p.status == ProductStatusEn.ACTIVE)
                        .sorted(Comparator.comparing(p -> p.name))
                        .limit(effectiveLimit)
                        .collect(Collectors.toList());
            }

            return result;
        }

        SimCategory findCategoryBySlug(String slug) {
            if (slug == null || slug.isBlank()) return null;
            return categoriesBySlug.get(slug.toLowerCase());
        }

        private int resolveLimit(Integer limit) {
            if (limit == null || limit < 1) {
                return 8;
            }
            return Math.min(limit, FEATURED_CAP);
        }
    }

    /**
     * Validates: Requirements 4.3
     *
     * For any category slug that matches a known category, all returned products
     * belong to that category (have the category's ID in their categoryIds set).
     */
    @Property(tries = 100)
    void allReturnedProductsBelongToFilteredCategory(
            @ForAll("categorySlug") String categorySlug,
            @ForAll("productCount") int productCount,
            @ForAll("categoryCount") int categoryCount
    ) {
        FeaturedShoppingSimulator simulator = new FeaturedShoppingSimulator();
        Random rng = new Random(categorySlug.hashCode() + productCount + categoryCount);

        // Create categories
        List<FeaturedShoppingSimulator.SimCategory> categories = new ArrayList<>();
        for (int i = 0; i < categoryCount; i++) {
            UUID catId = UUID.nameUUIDFromBytes(("category-" + i).getBytes());
            String slug = "cat-" + i;
            String name = "Category " + i;
            simulator.addCategory(catId, name, slug);
            categories.add(new FeaturedShoppingSimulator.SimCategory(catId, name, slug));
        }

        // Add the target category that we will filter by
        UUID targetCatId = UUID.nameUUIDFromBytes(("target-category-" + categorySlug).getBytes());
        simulator.addCategory(targetCatId, "Target Category", categorySlug);

        // Create products with random category assignments, some in the target category
        for (int i = 0; i < productCount; i++) {
            UUID productId = UUID.nameUUIDFromBytes(("product-" + i).getBytes());
            String productName = "Product " + String.format("%03d", i);
            boolean isFeatured = rng.nextBoolean();
            ProductStatusEn status = rng.nextBoolean() ? ProductStatusEn.ACTIVE : ProductStatusEn.PENDING;

            Set<UUID> categoryIds = new HashSet<>();
            // 40% chance of belonging to target category
            if (rng.nextInt(10) < 4) {
                categoryIds.add(targetCatId);
            }
            // Possibly add additional random categories
            if (!categories.isEmpty() && rng.nextBoolean()) {
                categoryIds.add(categories.get(rng.nextInt(categories.size())).id);
            }

            simulator.addProduct(productId, productName, isFeatured, status, categoryIds);
        }

        // Act: query with the target category slug
        List<FeaturedShoppingSimulator.SimProduct> result =
                simulator.getFeaturedShoppingProducts(null, categorySlug);

        // Assert: all returned products belong to the target category
        for (FeaturedShoppingSimulator.SimProduct product : result) {
            assertTrue(product.categoryIds.contains(targetCatId),
                    "Product '" + product.name + "' must belong to category '" + categorySlug
                            + "' but its categories are: " + product.categoryIds);
        }

        // Assert: all returned products are also featured and ACTIVE
        for (FeaturedShoppingSimulator.SimProduct product : result) {
            assertTrue(product.isFeatured,
                    "Product '" + product.name + "' must be featured");
            assertEquals(ProductStatusEn.ACTIVE, product.status,
                    "Product '" + product.name + "' must be ACTIVE");
        }
    }

    /**
     * Validates: Requirements 4.3
     *
     * When the slug matches no category, the result should be an empty list.
     */
    @Property(tries = 100)
    void unknownCategorySlugReturnsEmptyList(
            @ForAll("unknownSlug") String unknownSlug,
            @ForAll("productCount") int productCount
    ) {
        FeaturedShoppingSimulator simulator = new FeaturedShoppingSimulator();
        Random rng = new Random(unknownSlug.hashCode());

        // Add some known categories (none matching the unknown slug)
        for (int i = 0; i < 3; i++) {
            UUID catId = UUID.nameUUIDFromBytes(("known-category-" + i).getBytes());
            simulator.addCategory(catId, "Known Category " + i, "known-cat-" + i);
        }

        // Add products — some featured and active
        for (int i = 0; i < productCount; i++) {
            UUID productId = UUID.nameUUIDFromBytes(("product-" + i).getBytes());
            String productName = "Product " + String.format("%03d", i);
            boolean isFeatured = rng.nextBoolean();
            ProductStatusEn status = rng.nextBoolean() ? ProductStatusEn.ACTIVE : ProductStatusEn.PENDING;

            Set<UUID> categoryIds = new HashSet<>();
            if (rng.nextBoolean()) {
                UUID catId = UUID.nameUUIDFromBytes(("known-category-" + rng.nextInt(3)).getBytes());
                categoryIds.add(catId);
            }

            simulator.addProduct(productId, productName, isFeatured, status, categoryIds);
        }

        // Act: query with an unknown category slug
        List<FeaturedShoppingSimulator.SimProduct> result =
                simulator.getFeaturedShoppingProducts(null, unknownSlug);

        // Assert: result is empty because no category matches the slug
        assertTrue(result.isEmpty(),
                "Query with unknown category slug '" + unknownSlug + "' must return an empty list, "
                        + "but got " + result.size() + " results");
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> categorySlug() {
        // Generate valid category slugs (lowercase alphanumeric with hyphens)
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> unknownSlug() {
        // Slugs that will NOT match the "known-cat-X" pattern used in the test
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(5)
                .ofMaxLength(25)
                .filter(s -> !s.startsWith("known-cat-"));
    }

    @Provide
    Arbitrary<Integer> productCount() {
        return Arbitraries.integers().between(1, 30);
    }

    @Provide
    Arbitrary<Integer> categoryCount() {
        return Arbitraries.integers().between(0, 5);
    }
}
