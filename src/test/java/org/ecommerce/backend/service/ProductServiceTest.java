package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    ProductVariantRepository productVariantRepository;

    @InjectMock
    ProductImageRepository productImageRepository;

    @InjectMock
    ProductMapper productMapper;

    @Test
    void getAllProducts_shouldEnrichRepositoryDtosWithoutUsingEntitiesInService()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID productId = UUID.randomUUID();

        ProductListItemDto repositoryDto = new ProductListItemDto(
                productId.toString(),
                "Desk Lamp",
                "Warm light",
                null,
                List.of(),
                "Lighting",
                "BrightCo");

        ProductVariantEntity variant1 = new ProductVariantEntity();
        variant1.id = UUID.randomUUID();
        ProductVariantEntity variant2 = new ProductVariantEntity();
        variant2.id = UUID.randomUUID();

        ProductImageEntity featuredImage = new ProductImageEntity();
        featuredImage.imageUrl = "/images/lamp.jpg";
        featuredImage.isFeatured = true;

        when(productRepository.findAllProductListItems(pageRequest, filterRequest)).thenReturn(List.of(repositoryDto));
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(variant1, variant2));
        when(productImageRepository.findFeaturedByProductId(productId)).thenReturn(featuredImage);

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(variant1.id.toString(), variant2.id.toString()), repositoryDto.variantIds);
        assertEquals("/images/lamp.jpg", repositoryDto.imageName);
        assertEquals("Lighting", repositoryDto.categoryName);
        assertEquals("BrightCo", repositoryDto.brandName);

        verify(productRepository).findAllProductListItems(pageRequest, filterRequest);
    }

    @Test
    void getAllProducts_shouldDefaultDtoWhenRepositoryReturnsNullId()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        ProductListItemDto repositoryDto = new ProductListItemDto(
                null,
                "Draft Product",
                "No persisted id yet",
                null,
                List.of(),
                null,
                null);

        when(productRepository.findAllProductListItems(pageRequest, filterRequest)).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(), repositoryDto.variantIds);
        assertNull(repositoryDto.imageName);
        assertNull(repositoryDto.brandName);
    }


    @Test
    void getProductsOnSale_shouldReturnShoppingProductCardsFromRepository()
    {
        PageRequest pageRequest = new PageRequest();
        ProductShoppingListItemDto first = new ProductShoppingListItemDto();
        first.id = UUID.randomUUID().toString();
        first.name = "Promo Lamp";

        ProductShoppingListItemDto second = new ProductShoppingListItemDto();
        second.id = UUID.randomUUID().toString();
        second.name = "Promo Chair";

        when(productRepository.findOnSaleShoppingProductList(pageRequest)).thenReturn(List.of(first, second));

        List<ProductShoppingListItemDto> result = productService.getProductsOnSale(pageRequest);

        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(second, result.get(1));

        verify(productRepository).findOnSaleShoppingProductList(pageRequest);
    }

    @Test
    void getProductInformationDto_shouldMapNestedProductWithVariantsAndImages()
    {
        UUID productId = UUID.randomUUID();
        ProductEntity product = new ProductEntity();
        product.id = productId;
        product.name = "Desk Lamp";

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.id = UUID.randomUUID();

        List<ProductVariantEntity> variants = List.of(variant);
        ProductInformationDto mappedDto = new ProductInformationDto();

        when(productRepository.findByIdWithCategoryAndBrand(productId)).thenReturn(product);
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(variants);
        when(productMapper.mapToProductInformationDto(product, variants)).thenReturn(mappedDto);

        ProductInformationDto result = productService.getProductInformationDto(productId.toString());

        assertSame(mappedDto, result);
        verify(productRepository).findByIdWithCategoryAndBrand(productId);
        verify(productVariantRepository).findByVariantsForProductId(productId);
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
