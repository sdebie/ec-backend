package org.ecommerce.backend.service;

// Feature: wishlist-purchasing-rework, Property: Hydration returns all existing variants + flag derivation

import net.jqwik.api.*;
import org.ecommerce.common.dto.WishlistHydratedItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Properties:
 * <ol>
 *   <li>Hydration returns all existing variants regardless of status.</li>
 *   <li>Flag derivation: productActive = product ACTIVE; inStock = productActive AND variant ACTIVE AND stock > 0.</li>
 * </ol>
 * <p>
 * After the contract change (task 1.1), the repository no longer filters by status.
 * All requested variant IDs that exist are returned; nonexistent IDs are omitted.
 * Status-based flags (inStock, productActive) are derived in the service layer (task 1.3).
 * <p>
 * Validates: Requirements 1 (widened hydration contract), Requirements 1.2, 1.3 (flag derivation)
 */
class WishlistHydrationPropertyTest
{
    /**
     * Property: For any mix of variant statuses (ACTIVE/DISABLED) and product
     * statuses, ALL existing variants appear in the hydration response (none filtered
     * by status at the repository level).
     */
    @Property(tries = 100)
    void hydrationReturnsAllExistingVariantsRegardlessOfStatus(@ForAll("variantScenarios") VariantScenario scenario)
    {
        // Set up the service with mocked repositories
        WishlistHydrationService service = buildServiceWithMockedRepos(scenario);

        // Call hydrate with all variant IDs from the scenario
        List<UUID> requestedIds = scenario.variants.stream()
                .map(v -> v.variantId)
                .collect(Collectors.toList());

        List<WishlistHydratedItemDto> results = service.hydrate(requestedIds);

        // Property assertion: after the contract change, ALL variants are returned
        // regardless of status (flags are derived in the service layer — task 1.3).
        // For now, verify that every requested variant that exists appears in the response.
        Set<UUID> returnedVariantIds = results.stream()
                .map(dto -> dto.getVariantId())
                .collect(Collectors.toSet());

        Set<UUID> allRequestedIds = scenario.variants.stream()
                .map(v -> v.variantId)
                .collect(Collectors.toSet());

        // Assert: every variant in the scenario appears in results (none omitted by status)
        assertEquals(allRequestedIds, returnedVariantIds,
                "All existing variants must be returned regardless of status");
    }

    /**
     * Property: For ALL status/stock combinations, the derived flags match:
     * - productActive = product.status == ACTIVE
     * - inStock = productActive AND variant.status == ACTIVE AND stockQuantity != null AND stockQuantity > 0
     * <p>
     */
    @Property(tries = 200)
    void flagDerivationMatchesTruthTable(@ForAll("variantScenarios") VariantScenario scenario)
    {
        WishlistHydrationService service = buildServiceWithMockedRepos(scenario);

        List<UUID> requestedIds = scenario.variants.stream()
                .map(v -> v.variantId)
                .collect(Collectors.toList());

        List<WishlistHydratedItemDto> results = service.hydrate(requestedIds);

        // Build a lookup from variant ID to its definition for verification
        Map<UUID, VariantDef> defByVariantId = scenario.variants.stream()
                .collect(Collectors.toMap(v -> v.variantId, v -> v));

        for (WishlistHydratedItemDto dto : results) {
            VariantDef def = defByVariantId.get(dto.getVariantId());
            assertNotNull(def, "Every returned DTO must have a matching definition");

            // productActive = product.status == ACTIVE
            boolean expectedProductActive = def.productStatus == ProductStatusEn.ACTIVE;
            assertEquals(expectedProductActive, dto.getProductActive(),
                    "productActive must equal (product.status == ACTIVE) for " + def);

            // inStock = productActive AND variant ACTIVE AND stock > 0
            boolean expectedInStock = expectedProductActive
                    && def.variantStatus == ProductStatusEn.ACTIVE
                    && def.stockQuantity != null
                    && def.stockQuantity > 0;
            assertEquals(expectedInStock, dto.getInStock(),
                    "inStock must equal (productActive AND variant ACTIVE AND stock > 0) for " + def);
        }
    }

    // ── Service construction with mocked repositories ──────────────────────────

    private WishlistHydrationService buildServiceWithMockedRepos(VariantScenario scenario)
    {
        // Build entities for the scenario
        Map<UUID, ProductEntity> productEntities = new HashMap<>();
        List<ProductVariantEntity> allVariantEntities = new ArrayList<>();

        for (VariantDef def : scenario.variants) {
            // Get or create the product entity
            ProductEntity product = productEntities.computeIfAbsent(def.productId, id -> {
                ProductEntity p = new ProductEntity();
                p.setId(id);
                p.setName("Product-" + id.toString().substring(0, 8));
                p.setSlug("product-" + id.toString().substring(0, 8));
                p.setStatus(def.productStatus);
                return p;
            });

            ProductVariantEntity variant = new ProductVariantEntity();
            variant.setId(def.variantId);
            variant.setSku("SKU-" + def.variantId.toString().substring(0, 8));
            variant.setAttributesJson("{\"color\":\"blue\"}");
            variant.setStatus(def.variantStatus);
            variant.setStockQuantity(def.stockQuantity);
            variant.setProduct(product);
            allVariantEntities.add(variant);
        }

        // Create mock repositories — no status pre-filter; the new contract returns all
        // variants that exist, regardless of status (flags are derived in the service layer)
        ProductVariantRepository mockVariantRepo = new ProductVariantRepository()
        {
            @Override
            public List<ProductVariantEntity> findByIdsWithProduct(List<UUID> ids)
            {
                return allVariantEntities.stream()
                        .filter(v -> ids.contains(v.getId()))
                        .toList();
            }
        };

        VariantPricesRepository mockPricesRepo = new VariantPricesRepository()
        {
            @Override
            public List<VariantPricesEntity> findActiveForVariantIds(
                    List<UUID> variantIds, List<org.ecommerce.common.enums.PriceTypeEn> priceTypes,
                    java.time.LocalDateTime now)
            {
                return Collections.emptyList();
            }
        };

        ProductImageRepository mockImageRepo = new ProductImageRepository()
        {
            @Override
            public List<ProductImageEntity> findForVariantIds(List<UUID> variantIds)
            {
                return Collections.emptyList();
            }
        };

        // Inject mocks into the service via reflection
        WishlistHydrationService service = new WishlistHydrationService();
        setField(service, "productVariantRepository", mockVariantRepo);
        setField(service, "variantPricesRepository", mockPricesRepo);
        setField(service, "productImageRepository", mockImageRepo);
        return service;
    }

    private void setField(Object target, String fieldName, Object value)
    {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject mock field: " + fieldName, e);
        }
    }

    // ── Generators ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<VariantScenario> variantScenarios()
    {
        Arbitrary<List<VariantDef>> variantDefsArb = variantDefs().list().ofMinSize(1).ofMaxSize(15);

        return variantDefsArb.map(VariantScenario::new);
    }

    private Arbitrary<VariantDef> variantDefs()
    {
        Arbitrary<UUID> variantIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> productIdArb = Arbitraries.create(UUID::randomUUID);
        Arbitrary<ProductStatusEn> variantStatusArb = Arbitraries.of(ProductStatusEn.values());
        Arbitrary<ProductStatusEn> productStatusArb = Arbitraries.of(ProductStatusEn.values());
        // Stock: null, 0, or positive — covers all three branches of the inStock derivation
        Arbitrary<Integer> stockArb = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(0),
                Arbitraries.integers().between(1, 1000)
        );

        return Combinators.combine(variantIdArb, productIdArb, variantStatusArb, productStatusArb, stockArb)
                .as(VariantDef::new);
    }

    // ── Scenario classes ───────────────────────────────────────────────────────

    static class VariantDef
    {
        final UUID variantId;
        final UUID productId;
        final ProductStatusEn variantStatus;
        final ProductStatusEn productStatus;
        final Integer stockQuantity;

        VariantDef(UUID variantId, UUID productId, ProductStatusEn variantStatus, ProductStatusEn productStatus, Integer stockQuantity)
        {
            this.variantId = variantId;
            this.productId = productId;
            this.variantStatus = variantStatus;
            this.productStatus = productStatus;
            this.stockQuantity = stockQuantity;
        }

        @Override
        public String toString()
        {
            return "VariantDef{id=" + variantId.toString().substring(0, 8)
                    + ", productId=" + productId.toString().substring(0, 8)
                    + ", variantStatus=" + variantStatus
                    + ", productStatus=" + productStatus
                    + ", stock=" + stockQuantity + "}";
        }
    }

    static class VariantScenario
    {
        final List<VariantDef> variants;

        VariantScenario(List<VariantDef> variants)
        {
            this.variants = variants;
        }

        @Override
        public String toString()
        {
            long activeCount = variants.stream()
                    .filter(v -> v.variantStatus == ProductStatusEn.ACTIVE
                            && v.productStatus == ProductStatusEn.ACTIVE)
                    .count();
            return "VariantScenario{total=" + variants.size()
                    + ", bothActive=" + activeCount + "}";
        }
    }
}
