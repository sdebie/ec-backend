package org.ecommerce.backend.assembler;

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
        List<UUID> productIds = List.of(first.id, second.id);

        when(variants.findForProductIds(productIds, false)).thenReturn(List.of(firstVariant, secondVariant));
        when(images.findForListingProductIds(productIds)).thenReturn(List.of());
        when(prices.findActiveForProductIds(eq(productIds), any(), eq(now), eq(false)))
                .thenReturn(List.of(firstPrice, secondPrice));

        List<ProductShoppingListItemDto> result = assembler.buildShoppingListItems(List.of(first, second), now, false);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).variantCount);
        assertEquals(new BigDecimal("10.00"), result.get(0).retailPrice.price);
        assertEquals(new BigDecimal("20.00"), result.get(1).retailPrice.price);
        verify(variants).findForProductIds(productIds, false);
        verify(images).findForListingProductIds(productIds);
        verify(prices).findActiveForProductIds(eq(productIds), any(), eq(now), eq(false));
        verify(variants, never()).countForProduct(any(), anyBoolean());
        verify(variants, never()).findFirstVariantId(any(), anyBoolean());
        verify(prices, never()).findLowestActive(any(), any(), any(), anyBoolean());
    }

    private ProductEntity product(String name) {
        ProductEntity product = new ProductEntity();
        product.id = UUID.randomUUID();
        product.name = name;
        product.slug = name.toLowerCase();
        product.productType = ProductTypeEn.SIMPLE;
        product.status = ProductStatusEn.ACTIVE;
        return product;
    }

    private ProductVariantEntity variant(ProductEntity product, String sku) {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = UUID.randomUUID();
        variant.product = product;
        variant.sku = sku;
        variant.status = ProductStatusEn.ACTIVE;
        return variant;
    }

    private VariantPricesEntity price(ProductVariantEntity variant, BigDecimal amount, LocalDateTime now) {
        VariantPricesEntity price = new VariantPricesEntity();
        price.id = UUID.randomUUID();
        price.variant = variant;
        price.priceType = PriceTypeEn.RETAIL_PRICE;
        price.price = amount;
        price.createdAt = now;
        return price;
    }
}
