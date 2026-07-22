package org.ecommerce.backend.service;

// Feature: wishlist-completion, Property 6: Hydration omits inactive products

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
 * Property 6: Hydration omits inactive products
 * <p>
 * For any set of variant IDs passed to the hydration endpoint, the response SHALL
 * contain only entries where both the product status is ACTIVE and the variant status
 * is ACTIVE. No entry with a DISABLED or PENDING product/variant SHALL appear in the
 * response.
 * <p>
 * Validates: Requirements 3.7
 */
class WishlistHydrationPropertyTest
{
    /**
     * Property: For any mix of variant statuses (ACTIVE/DISABLED/PENDING) and product
     * statuses, only those with BOTH product.status = ACTIVE and variant.status = ACTIVE
     * appear in the hydration response.
     */
    @Property(tries = 100)
    void hydrationOnlyReturnsVariantsWithBothProductAndVariantActive(@ForAll("variantScenarios") VariantScenario scenario)
    {
        // Set up the service with mocked repositories
        WishlistHydrationService service = buildServiceWithMockedRepos(scenario);

        // Call hydrate with all variant IDs from the scenario
        List<UUID> requestedIds = scenario.variants.stream()
                .map(v -> v.variantId)
                .collect(Collectors.toList());

        List<WishlistHydratedItemDto> results = service.hydrate(requestedIds);

        // Property assertion: every returned item must correspond to a variant
        // where both product.status == ACTIVE and variant.status == ACTIVE
        Set<UUID> returnedVariantIds = results.stream()
                .map(dto -> dto.getVariantId())
                .collect(Collectors.toSet());

        // Determine which variant IDs SHOULD be in the response
        Set<UUID> expectedActiveIds = scenario.variants.stream()
                .filter(v -> v.variantStatus == ProductStatusEn.ACTIVE
                        && v.productStatus == ProductStatusEn.ACTIVE)
                .map(v -> v.variantId)
                .collect(Collectors.toSet());

        // Determine which variant IDs MUST NOT be in the response
        Set<UUID> inactiveIds = scenario.variants.stream()
                .filter(v -> v.variantStatus != ProductStatusEn.ACTIVE
                        || v.productStatus != ProductStatusEn.ACTIVE)
                .map(v -> v.variantId)
                .collect(Collectors.toSet());

        // Assert: no inactive variant appears in results
        for (UUID inactiveId : inactiveIds) {
            assertFalse(returnedVariantIds.contains(inactiveId), "Variant " + inactiveId + " has inactive product/variant status and must NOT appear in results");
        }

        // Assert: all active variants DO appear in results
        for (UUID activeId : expectedActiveIds) {
            assertTrue(returnedVariantIds.contains(activeId), "Variant " + activeId + " has ACTIVE product and variant status and MUST appear in results");
        }

        // Assert: result count equals exactly the number of both-ACTIVE entries
        assertEquals(expectedActiveIds.size(), results.size(), "Result count must equal number of variants with both product and variant ACTIVE");
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
            variant.setProduct(product);
            allVariantEntities.add(variant);
        }

        // Filter to only ACTIVE variants with ACTIVE products (mimics the DB query)
        List<ProductVariantEntity> activeVariants = allVariantEntities.stream()
                .filter(v -> v.getStatus() == ProductStatusEn.ACTIVE && v.getProduct().getStatus() == ProductStatusEn.ACTIVE)
                .toList();

        // Create mock repositories
        ProductVariantRepository mockVariantRepo = new ProductVariantRepository()
        {
            @Override
            public List<ProductVariantEntity> findActiveByIdsWithProduct(List<UUID> ids)
            {
                return activeVariants.stream()
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

        return Combinators.combine(variantIdArb, productIdArb, variantStatusArb, productStatusArb).as(VariantDef::new);
    }

    // ── Scenario classes ───────────────────────────────────────────────────────

    static class VariantDef
    {
        final UUID variantId;
        final UUID productId;
        final ProductStatusEn variantStatus;
        final ProductStatusEn productStatus;

        VariantDef(UUID variantId, UUID productId, ProductStatusEn variantStatus, ProductStatusEn productStatus)
        {
            this.variantId = variantId;
            this.productId = productId;
            this.variantStatus = variantStatus;
            this.productStatus = productStatus;
        }

        @Override
        public String toString()
        {
            return "VariantDef{id=" + variantId.toString().substring(0, 8)
                    + ", productId=" + productId.toString().substring(0, 8)
                    + ", variantStatus=" + variantStatus
                    + ", productStatus=" + productStatus + "}";
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
