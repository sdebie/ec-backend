package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ProductServiceTest
{
    @Inject
    ProductService productService;

    @InjectMock
    ProductRepository productRepository;

    @InjectMock
    org.ecommerce.backend.assembler.ProductListItemAssembler productListItemAssembler;

    @InjectMock
    ProductVariantRepository productVariantRepository;

    @InjectMock
    ProductMapper productMapper;

    @Test
    void getProductsOnSale_shouldReturnShoppingProductCardsFromRepository()
    {
        PageRequest pageRequest = new PageRequest();
        ProductEntity p1 = new ProductEntity();
        p1.setId(UUID.randomUUID());
        p1.setName("Promo Lamp");
        ProductEntity p2 = new ProductEntity();
        p2.setId(UUID.randomUUID());
        p2.setName("Promo Chair");

        ProductShoppingListItemDto first = new ProductShoppingListItemDto();
        first.setName("Promo Lamp");
        ProductShoppingListItemDto second = new ProductShoppingListItemDto();
        second.setName("Promo Chair");

        when(productRepository.findOnSaleProductEntities(pageRequest, false)).thenReturn(List.of(p1, p2));
        when(productListItemAssembler.buildShoppingListItems(anyList(), any(), eq(false))).thenReturn(List.of(first, second));
        when(productRepository.countOnSaleProducts(false)).thenReturn(2L);

        org.ecommerce.common.dto.PageResponse<ProductShoppingListItemDto> result = productService.getProductsOnSale(pageRequest, false);

        assertEquals(2, result.getContent().size());
        assertSame(first, result.getContent().get(0));
        assertSame(second, result.getContent().get(1));
        assertEquals(2L, result.getTotalElements());

        verify(productRepository).findOnSaleProductEntities(pageRequest, false);
        verify(productListItemAssembler).buildShoppingListItems(eq(List.of(p1, p2)), org.mockito.ArgumentMatchers.any(), eq(false));
    }

    @Test
    void getProductInformationDto_shouldMapNestedProductWithVariantsAndImages()
    {
        UUID productId = UUID.randomUUID();
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        product.setName("Desk Lamp");

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setId(UUID.randomUUID());

        List<ProductVariantEntity> variants = List.of(variant);
        ProductInformationDto mappedDto = new ProductInformationDto();

        when(productRepository.findByIdWithCategoryAndBrand(productId)).thenReturn(product);
        when(productVariantRepository.findActiveVariantsForProductId(productId)).thenReturn(variants);
        when(productMapper.mapToProductInformationDto(product, variants)).thenReturn(mappedDto);

        ProductInformationDto result = productService.getProductInformationDto(productId.toString());

        assertSame(mappedDto, result);
        verify(productRepository).findByIdWithCategoryAndBrand(productId);
        verify(productVariantRepository).findActiveVariantsForProductId(productId);
        verify(productMapper).mapToProductInformationDto(product, variants);
    }

    @Test
    void getProductInformationDto_shouldReturnNullWhenProductDoesNotExist()
    {
        UUID productId = UUID.randomUUID();

        when(productRepository.findByIdWithCategoryAndBrand(productId)).thenReturn(null);

        ProductInformationDto result = productService.getProductInformationDto(productId.toString());

        assertNull(result);
        verify(productRepository).findByIdWithCategoryAndBrand(productId);
    }

}
