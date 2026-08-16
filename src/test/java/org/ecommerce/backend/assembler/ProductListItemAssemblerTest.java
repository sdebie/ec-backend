package org.ecommerce.backend.assembler;

import org.ecommerce.backend.mapper.VariantPriceMapperImpl;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductListItemAssemblerTest {

    @Test
    void buildShoppingListItems_preloadsPageDataOnceInsteadOfQueryingPerProduct() {
        ProductVariantRepository variants = mock(ProductVariantRepository.class);
        ProductImageRepository images = mock(ProductImageRepository.class);
        VariantPricesRepository prices = mock(VariantPricesRepository.class);

        ProductListItemAssembler assembler = new ProductListItemAssembler();
        assembler.variantPriceMapper = new VariantPriceMapperImpl();
        assembler.variantRepository = variants;
        assembler.imageRepository = images;
        assembler.variantPricesRepository = prices;
        assembler.productMapper = mock(ProductMapper.class);

        ProductEntity first = product("First");
        ProductEntity second = product("Second");
        ProductVariantEntity firstVariant = variant(first, "FIRST-1");
        ProductVariantEntity secondVariant = variant(second, "SECOND-1");
        LocalDateTime now = LocalDateTime.now();
        VariantPricesEntity firstPrice = price(firstVariant, new BigDecimal("10.00"), now);
        VariantPricesEntity secondPrice = price(secondVariant, new BigDecimal("20.00"), now);
        List<UUID> productIds = List.of(first.getId(), second.getId());

        when(variants.findForProductIds(productIds, false)).thenReturn(List.of(firstVariant, secondVariant));
        when(images.findForListingProductIds(productIds)).thenReturn(List.of());
        when(prices.findActiveForProductIds(eq(productIds), any(), eq(now), eq(false)))
                .thenReturn(List.of(firstPrice, secondPrice));

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(first, second), now, false);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getVariantCount());
        assertEquals(new BigDecimal("10.00"), result.get(0).getRetailPrice().getPrice());
        assertEquals(new BigDecimal("20.00"), result.get(1).getRetailPrice().getPrice());
        verify(variants).findForProductIds(productIds, false);
        verify(images).findForListingProductIds(productIds);
        verify(prices).findActiveForProductIds(eq(productIds), any(), eq(now), eq(false));
        verify(variants, never()).countForProduct(any(), anyBoolean());
        verify(variants, never()).findFirstVariantId(any(), anyBoolean());
        verify(prices, never()).findLowestActive(any(), any(), any(), anyBoolean());
    }

    @Test
    void buildShoppingListItems_simpleProductWithVariant_populatesSkuAndInStock() {
        ProductListItemAssembler assembler = assemblerWithMocks();
        ProductEntity product = product("Simple Product");
        product.setProductType(ProductTypeEn.SIMPLE);
        ProductVariantEntity v = variant(product, "SKU-001");
        v.setStockQuantity(5);
        LocalDateTime now = LocalDateTime.now();

        when(assembler.variantRepository.findForProductIds(any(), eq(false)))
                .thenReturn(List.of(v));
        when(assembler.imageRepository.findForListingProductIds(any()))
                .thenReturn(List.of());
        when(assembler.variantPricesRepository.findActiveForProductIds(any(), any(), any(), eq(false)))
                .thenReturn(List.of());

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(product), now, false);

        assertEquals("SKU-001", result.get(0).getSku());
        assertTrue(result.get(0).getInStock());
    }

    @Test
    void buildShoppingListItems_variableProduct_skuIsNull() {
        ProductListItemAssembler assembler = assemblerWithMocks();
        ProductEntity product = product("Variable Product");
        product.setProductType(ProductTypeEn.VARIABLE);
        ProductVariantEntity v = variant(product, "VAR-SKU-001");
        v.setStockQuantity(10);
        LocalDateTime now = LocalDateTime.now();

        when(assembler.variantRepository.findForProductIds(any(), eq(false)))
                .thenReturn(List.of(v));
        when(assembler.imageRepository.findForListingProductIds(any()))
                .thenReturn(List.of());
        when(assembler.variantPricesRepository.findActiveForProductIds(any(), any(), any(), eq(false)))
                .thenReturn(List.of());

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(product), now, false);

        assertNull(result.get(0).getSku());
        assertTrue(result.get(0).getInStock());
    }

    @Test
    void buildShoppingListItems_noVariants_inStockIsFalse() {
        ProductListItemAssembler assembler = assemblerWithMocks();
        ProductEntity product = product("No Variants");
        product.setProductType(ProductTypeEn.SIMPLE);
        LocalDateTime now = LocalDateTime.now();

        when(assembler.variantRepository.findForProductIds(any(), eq(false)))
                .thenReturn(List.of());
        when(assembler.imageRepository.findForListingProductIds(any()))
                .thenReturn(List.of());
        when(assembler.variantPricesRepository.findActiveForProductIds(any(), any(), any(), eq(false)))
                .thenReturn(List.of());

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(product), now, false);

        assertNull(result.get(0).getSku());
        assertFalse(result.get(0).getInStock());
    }

    @Test
    void buildShoppingListItems_nullStockQuantity_treatedAsZero() {
        ProductListItemAssembler assembler = assemblerWithMocks();
        ProductEntity product = product("Null Stock");
        product.setProductType(ProductTypeEn.SIMPLE);
        ProductVariantEntity v = variant(product, "NULL-STOCK-SKU");
        v.setStockQuantity(null);
        LocalDateTime now = LocalDateTime.now();

        when(assembler.variantRepository.findForProductIds(any(), eq(false)))
                .thenReturn(List.of(v));
        when(assembler.imageRepository.findForListingProductIds(any()))
                .thenReturn(List.of());
        when(assembler.variantPricesRepository.findActiveForProductIds(any(), any(), any(), eq(false)))
                .thenReturn(List.of());

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(product), now, false);

        assertEquals("NULL-STOCK-SKU", result.get(0).getSku());
        assertFalse(result.get(0).getInStock());
    }

    @Test
    void buildShoppingListItems_noNewQueriesIntroduced() {
        ProductVariantRepository variants = mock(ProductVariantRepository.class);
        ProductImageRepository images = mock(ProductImageRepository.class);
        VariantPricesRepository prices = mock(VariantPricesRepository.class);

        ProductListItemAssembler assembler = new ProductListItemAssembler();
        assembler.variantPriceMapper = new VariantPriceMapperImpl();
        assembler.variantRepository = variants;
        assembler.imageRepository = images;
        assembler.variantPricesRepository = prices;
        assembler.productMapper = mock(ProductMapper.class);

        ProductEntity product = product("Query Check");
        ProductVariantEntity v = variant(product, "QC-1");
        v.setStockQuantity(3);
        LocalDateTime now = LocalDateTime.now();
        List<UUID> productIds = List.of(product.getId());

        when(variants.findForProductIds(productIds, false)).thenReturn(List.of(v));
        when(images.findForListingProductIds(productIds)).thenReturn(List.of());
        when(prices.findActiveForProductIds(eq(productIds), any(), eq(now), eq(false)))
                .thenReturn(List.of());

        assembler.buildShoppingListItems(List.of(product), now, false);

        // Exactly the same three preload calls — no additional queries
        verify(variants).findForProductIds(productIds, false);
        verify(images).findForListingProductIds(productIds);
        verify(prices).findActiveForProductIds(eq(productIds), any(), eq(now), eq(false));
        verify(variants, never()).countForProduct(any(), anyBoolean());
        verify(variants, never()).findFirstVariantId(any(), anyBoolean());
        verify(prices, never()).findLowestActive(any(), any(), any(), anyBoolean());
    }

    private ProductListItemAssembler assemblerWithMocks() {
        ProductListItemAssembler assembler = new ProductListItemAssembler();
        assembler.variantPriceMapper = new VariantPriceMapperImpl();
        assembler.variantRepository = mock(ProductVariantRepository.class);
        assembler.imageRepository = mock(ProductImageRepository.class);
        assembler.variantPricesRepository = mock(VariantPricesRepository.class);
        assembler.productMapper = mock(ProductMapper.class);
        return assembler;
    }

    private ProductEntity product(String name) {
        ProductEntity product = new ProductEntity();
        product.setId(UUID.randomUUID());
        product.setName(name);
        product.setSlug(name.toLowerCase());
        product.setProductType(ProductTypeEn.SIMPLE);
        product.setStatus(ProductStatusEn.ACTIVE);
        return product;
    }

    private ProductVariantEntity variant(ProductEntity product, String sku) {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setStatus(ProductStatusEn.ACTIVE);
        return variant;
    }

    private VariantPricesEntity price(ProductVariantEntity variant, BigDecimal amount, LocalDateTime now) {
        VariantPricesEntity price = new VariantPricesEntity();
        price.setId(UUID.randomUUID());
        price.setVariant(variant);
        price.setPriceType(PriceTypeEn.RETAIL_PRICE);
        price.setPrice(amount);
        price.setCreatedAt(now);
        return price;
    }
}
