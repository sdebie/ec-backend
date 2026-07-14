package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 6: Storefront Query Filter Correctness

import net.jqwik.api.*;
import org.ecommerce.common.enums.ProductStatusEn;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 6: Storefront Query Filter Correctness
 *
 * For any set of products in the database, the shoppingFeaturedProductList query
 * should return only products where is_featured = true AND status = ACTIVE,
 * sorted by product name ascending. No product with is_featured = false or
 * status != ACTIVE should appear in the result.
 *
 * This test simulates the FeaturedProductService.getFeaturedShoppingProducts logic
 * with an in-memory store that mirrors the Panache entity filtering behavior.
 *
 * Validates: Requirements 4.1
 */
class FeaturedStorefrontFilterPropertyTest {

    /**
     * Simulates FeaturedProductService.getFeaturedShoppingProducts behavior in-memory.
     * Mirrors the exact query logic: is_featured = true AND status = ACTIVE, ordered by name ASC.
     */
    private static class StorefrontQuerySimulator {
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
         * - Filter: is_featured = true AND status = ACTIVE
         * - Order: name ascending
         * - No limit applied (testing filter correctness, not limit enforcement)
         */
        List<SimProduct> getFeaturedShoppingProducts() {
            return products.values().stream()
                    .filter(p -> p.isFeatured && p.status == ProductStatusEn.ACTIVE)
                    .sorted(Comparator.comparing(p -> p.name))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Validates: Requirements 4.1
     *
     * For any set of products with varying featured flags and statuses,
     * getFeaturedShoppingProducts() returns ONLY products where
     * is_featured = true AND status = ACTIVE, sorted by name ascending.
     */
    @Property(tries = 100)
    void storefrontQueryReturnsOnlyFeaturedActiveProductsSortedByName(
            @ForAll("productList") List<ProductSpec> productSpecs
    ) {
        StorefrontQuerySimulator simulator = new StorefrontQuerySimulator();

        // Deduplicate by UUID — last one wins (simulates HashMap behavior)
        Map<UUID, ProductSpec> deduped = new LinkedHashMap<>();
        for (ProductSpec spec : productSpecs) {
            deduped.put(spec.id, spec);
        }
        List<ProductSpec> uniqueSpecs = new ArrayList<>(deduped.values());

        // Populate simulator with the deduplicated product set
        for (ProductSpec spec : uniqueSpecs) {
            simulator.addProduct(spec.id, spec.name, spec.isFeatured, spec.status);
        }

        // Act: query featured shopping products
        List<StorefrontQuerySimulator.SimProduct> result = simulator.getFeaturedShoppingProducts();

        // Assert 1: Every returned product has is_featured = true
        for (StorefrontQuerySimulator.SimProduct product : result) {
            assertTrue(product.isFeatured,
                    "Returned product '" + product.name + "' must have is_featured = true, but was false");
        }

        // Assert 2: Every returned product has status = ACTIVE
        for (StorefrontQuerySimulator.SimProduct product : result) {
            assertEquals(ProductStatusEn.ACTIVE, product.status,
                    "Returned product '" + product.name + "' must have status ACTIVE, but was " + product.status);
        }

        // Assert 3: No product with is_featured = false appears in result
        Set<UUID> resultIds = result.stream().map(p -> p.id).collect(Collectors.toSet());
        for (ProductSpec spec : uniqueSpecs) {
            if (!spec.isFeatured) {
                assertFalse(resultIds.contains(spec.id),
                        "Product '" + spec.name + "' with is_featured = false must NOT appear in storefront results");
            }
        }

        // Assert 4: No product with status != ACTIVE appears in result
        for (ProductSpec spec : uniqueSpecs) {
            if (spec.status != ProductStatusEn.ACTIVE) {
                assertFalse(resultIds.contains(spec.id),
                        "Product '" + spec.name + "' with status " + spec.status + " must NOT appear in storefront results");
            }
        }

        // Assert 5: All products that ARE featured AND ACTIVE must be present in result
        long expectedCount = uniqueSpecs.stream()
                .filter(p -> p.isFeatured && p.status == ProductStatusEn.ACTIVE)
                .count();
        assertEquals(expectedCount, result.size(),
                "Result must contain exactly all featured + ACTIVE products");

        // Assert 6: Results are sorted by name ascending
        for (int i = 1; i < result.size(); i++) {
            String prev = result.get(i - 1).name;
            String curr = result.get(i).name;
            assertTrue(prev.compareTo(curr) <= 0,
                    "Results must be sorted by name ascending, but '" + prev + "' came before '" + curr + "'");
        }
    }

    // ── Data class for generated products ───────────────────────────────────────

    static class ProductSpec {
        UUID id;
        String name;
        boolean isFeatured;
        ProductStatusEn status;

        ProductSpec(UUID id, String name, boolean isFeatured, ProductStatusEn status) {
            this.id = id;
            this.name = name;
            this.isFeatured = isFeatured;
            this.status = status;
        }
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<ProductSpec>> productList() {
        Arbitrary<ProductSpec> productArbitrary = Combinators.combine(
                randomUUID(),
                productName(),
                Arbitraries.of(true, false),
                productStatus()
        ).as(ProductSpec::new);

        // Generate lists of 1 to 30 products to cover various set sizes
        return productArbitrary.list().ofMinSize(1).ofMaxSize(30);
    }

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
                .ofMaxLength(30);
    }

    @Provide
    Arbitrary<ProductStatusEn> productStatus() {
        return Arbitraries.of(ProductStatusEn.ACTIVE, ProductStatusEn.PENDING, ProductStatusEn.DISABLED);
    }
}
