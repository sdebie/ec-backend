package org.ecommerce.backend.service;

// Feature: wishlist-purchasing-rework, Task 1.4: Flag derivation truth table

import org.ecommerce.backend.mapper.VariantPriceMapperImpl;
import org.ecommerce.common.dto.WishlistHydratedItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic unit tests for WishlistHydrationService flag derivation (task 1.4).
 * <p>
 * Tests the truth table defined in the design:
 * <ul>
 *   <li>ACTIVE variant + ACTIVE product + stock > 0 → inStock: true, productActive: true</li>
 *   <li>ACTIVE variant + ACTIVE product + stock 0 → inStock: false, productActive: true</li>
 *   <li>ACTIVE variant + ACTIVE product + stock null → inStock: false, productActive: true</li>
 *   <li>DISABLED variant + ACTIVE product → inStock: false, productActive: true</li>
 *   <li>Any variant + DISABLED product → inStock: false, productActive: false</li>
 *   <li>Nonexistent ID → omitted from response</li>
 * </ul>
 * <p>
 */
class WishlistHydrationFlagsTest
{
    private WishlistHydrationService service;
    private List<ProductVariantEntity> variants;

    @BeforeEach
    void setUp()
    {
        variants = new ArrayList<>();
        service = buildService();
    }

    @Test
    @DisplayName("ACTIVE variant + ACTIVE product + stock > 0 → inStock: true, productActive: true")
    void activeVariantActiveProductStockPositive()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.ACTIVE, ProductStatusEn.ACTIVE, 5);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertEquals(variantId, dto.getVariantId());
        assertTrue(dto.getInStock(), "inStock should be true when variant ACTIVE, product ACTIVE, stock > 0");
        assertTrue(dto.getProductActive(), "productActive should be true when product is ACTIVE");
    }

    @Test
    @DisplayName("ACTIVE variant + ACTIVE product + stock 0 → inStock: false, productActive: true")
    void activeVariantActiveProductStockZero()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.ACTIVE, ProductStatusEn.ACTIVE, 0);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertFalse(dto.getInStock(), "inStock should be false when stock is 0");
        assertTrue(dto.getProductActive(), "productActive should be true when product is ACTIVE");
    }

    @Test
    @DisplayName("ACTIVE variant + ACTIVE product + stock null → inStock: false, productActive: true")
    void activeVariantActiveProductStockNull()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.ACTIVE, ProductStatusEn.ACTIVE, null);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertFalse(dto.getInStock(), "inStock should be false when stock is null");
        assertTrue(dto.getProductActive(), "productActive should be true when product is ACTIVE");
    }

    @Test
    @DisplayName("DISABLED variant + ACTIVE product → inStock: false, productActive: true")
    void disabledVariantActiveProduct()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.DISABLED, ProductStatusEn.ACTIVE, 10);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertFalse(dto.getInStock(), "inStock should be false when variant is DISABLED");
        assertTrue(dto.getProductActive(), "productActive should be true when product is ACTIVE");
    }

    @Test
    @DisplayName("ACTIVE variant + DISABLED product → inStock: false, productActive: false")
    void activeVariantDisabledProduct()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.ACTIVE, ProductStatusEn.DISABLED, 10);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertFalse(dto.getInStock(), "inStock should be false when product is DISABLED");
        assertFalse(dto.getProductActive(), "productActive should be false when product is DISABLED");
    }

    @Test
    @DisplayName("DISABLED variant + DISABLED product → inStock: false, productActive: false")
    void disabledVariantDisabledProduct()
    {
        UUID variantId = UUID.randomUUID();
        addVariant(variantId, ProductStatusEn.DISABLED, ProductStatusEn.DISABLED, 5);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(variantId));

        assertEquals(1, results.size());
        WishlistHydratedItemDto dto = results.get(0);
        assertFalse(dto.getInStock(), "inStock should be false when product is DISABLED");
        assertFalse(dto.getProductActive(), "productActive should be false when product is DISABLED");
    }

    @Test
    @DisplayName("Nonexistent variant ID → omitted from response")
    void nonexistentIdOmitted()
    {
        UUID existingId = UUID.randomUUID();
        UUID nonexistentId = UUID.randomUUID();
        addVariant(existingId, ProductStatusEn.ACTIVE, ProductStatusEn.ACTIVE, 5);

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(existingId, nonexistentId));

        assertEquals(1, results.size(), "Only the existing variant should be returned");
        assertEquals(existingId, results.get(0).getVariantId());
    }

    @Test
    @DisplayName("All nonexistent IDs → empty response")
    void allNonexistentIdsEmpty()
    {
        UUID nonexistentId1 = UUID.randomUUID();
        UUID nonexistentId2 = UUID.randomUUID();

        List<WishlistHydratedItemDto> results = service.hydrate(List.of(nonexistentId1, nonexistentId2));

        assertTrue(results.isEmpty(), "All-nonexistent request should return empty");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void addVariant(UUID variantId, ProductStatusEn variantStatus, ProductStatusEn productStatus, Integer stockQuantity)
    {
        ProductEntity product = new ProductEntity();
        product.setId(UUID.randomUUID());
        product.setName("Product-" + product.getId().toString().substring(0, 8));
        product.setSlug("product-" + product.getId().toString().substring(0, 8));
        product.setStatus(productStatus);

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(variantId);
        variant.setSku("SKU-" + variantId.toString().substring(0, 8));
        variant.setAttributesJson("{\"color\":\"red\"}");
        variant.setStatus(variantStatus);
        variant.setStockQuantity(stockQuantity);
        variant.setProduct(product);

        variants.add(variant);
    }

    private WishlistHydrationService buildService()
    {
        ProductVariantRepository mockVariantRepo = new ProductVariantRepository()
        {
            @Override
            public List<ProductVariantEntity> findByIdsWithProduct(List<UUID> ids)
            {
                return variants.stream()
                        .filter(v -> ids.contains(v.getId()))
                        .toList();
            }
        };

        VariantPricesRepository mockPricesRepo = new VariantPricesRepository()
        {
            @Override
            public List<VariantPricesEntity> findActiveForVariantIds(
                    List<UUID> variantIds, List<PriceTypeEn> priceTypes, LocalDateTime now)
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

        WishlistHydrationService svc = new WishlistHydrationService();
        svc.variantPriceMapper = new VariantPriceMapperImpl();
        setField(svc, "productVariantRepository", mockVariantRepo);
        setField(svc, "variantPricesRepository", mockPricesRepo);
        setField(svc, "productImageRepository", mockImageRepo);
        return svc;
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
}
