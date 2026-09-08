package org.ecommerce.backend.service;

import org.ecommerce.backend.mapper.WishlistItemMapper;
import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WishlistServiceGetItemsTest
{
    private ProductVariantRepository productVariantRepository;
    private VariantPricesRepository variantPricesRepository;
    private ProductImageRepository productImageRepository;
    private WishlistItemMapper wishlistItemMapper;
    private WishlistService service;

    @BeforeEach
    void setUp()
    {
        productVariantRepository = mock(ProductVariantRepository.class);
        variantPricesRepository = mock(VariantPricesRepository.class);
        productImageRepository = mock(ProductImageRepository.class);
        wishlistItemMapper = mock(WishlistItemMapper.class);

        service = new WishlistService();
        service.productVariantRepository = productVariantRepository;
        service.variantPricesRepository = variantPricesRepository;
        service.productImageRepository = productImageRepository;
        service.wishlistItemMapper = wishlistItemMapper;
    }

    @Test
    void getItemsReturnsEmptyWithoutHittingPersistenceWhenNothingIsRequested()
    {
        assertTrue(service.getItems(null).isEmpty());
        assertTrue(service.getItems(List.of()).isEmpty());
        verify(productVariantRepository, never()).findByIdsWithProduct(anyList());
    }

    @Test
    void getItemsRejectsMoreThanFiftyIds()
    {
        List<UUID> ids = IntStream.range(0, WishlistService.MAX_ITEM_IDS + 1)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.getItems(ids));

        assertEquals("Maximum 50 variant IDs per request", ex.getMessage());
        verify(productVariantRepository, never()).findByIdsWithProduct(anyList());
    }

    @Test
    void getItemsReturnsTheMapperResultForEachResolvedVariant()
    {
        UUID variantId = UUID.randomUUID();
        ProductEntity product = new ProductEntity();
        product.setId(UUID.randomUUID());
        product.setName("Lamp");
        product.setSlug("lamp");
        product.setStatus(ProductStatusEn.ACTIVE);

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(variantId);
        variant.setSku("SKU-1");
        variant.setStatus(ProductStatusEn.ACTIVE);
        variant.setStockQuantity(4);
        variant.setProduct(product);

        WishlistItemDto mapped = new WishlistItemDto();
        mapped.setVariantId(variantId);

        when(productVariantRepository.findByIdsWithProduct(List.of(variantId))).thenReturn(List.of(variant));
        when(variantPricesRepository.findActiveForVariantIds(anyList(), anyList(), any())).thenReturn(List.of());
        when(productImageRepository.findForVariantIds(List.of(variantId))).thenReturn(List.of());
        when(wishlistItemMapper.toDto(eq(variant), any(), any(), any())).thenReturn(mapped);

        List<WishlistItemDto> items = service.getItems(List.of(variantId));

        assertEquals(1, items.size());
        assertSame(mapped, items.getFirst());
        verify(wishlistItemMapper).toDto(eq(variant), any(), any(), any());
    }
}
