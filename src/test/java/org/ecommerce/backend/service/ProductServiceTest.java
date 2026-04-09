package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.SaleVariantDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                null,
                null,
                null,
                List.of(),
                List.of(),
                "Lighting");

        ProductVariantEntity variant1 = new ProductVariantEntity();
        variant1.id = UUID.randomUUID();
        ProductVariantEntity variant2 = new ProductVariantEntity();
        variant2.id = UUID.randomUUID();

        ProductImageDto imageDto = new ProductImageDto("img-1", "/images/lamp.jpg", 1, true);

        when(productRepository.findAllProductListItems(pageRequest, filterRequest)).thenReturn(List.of(repositoryDto));
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(variant1, variant2));
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        when(productMapper.mapImageEntitiesToDtos(List.of())).thenReturn(List.of(imageDto));
        when(productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_PRICE)).thenReturn(new BigDecimal("19.99"));
        when(productVariantRepository.getMinimumPrice(productId, PriceTypeEn.RETAIL_SALE_PRICE)).thenReturn(new BigDecimal("17.99"));
        when(productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_PRICE)).thenReturn(new BigDecimal("12.99"));
        when(productVariantRepository.getMinimumPrice(productId, PriceTypeEn.WHOLESALE_SALE_PRICE)).thenReturn(new BigDecimal("10.99"));

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(variant1.id.toString(), variant2.id.toString()), repositoryDto.variantIds);
        assertEquals(List.of(imageDto), repositoryDto.productImages);
        assertEquals(new BigDecimal("19.99"), repositoryDto.retailPrice);
        assertEquals(new BigDecimal("17.99"), repositoryDto.retailSalesPrice);
        assertEquals(new BigDecimal("12.99"), repositoryDto.wholesalePrice);
        assertEquals(new BigDecimal("10.99"), repositoryDto.wholesaleSalesPrice);
        assertEquals("Lighting", repositoryDto.categoryName);

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
                null,
                null,
                null,
                List.of(),
                List.of(),
                null);

        when(productRepository.findAllProductListItems(pageRequest, filterRequest)).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(), repositoryDto.variantIds);
        assertEquals(List.of(), repositoryDto.productImages);
        assertEquals(BigDecimal.ZERO, repositoryDto.retailPrice);
        assertEquals(BigDecimal.ZERO, repositoryDto.retailSalesPrice);
        assertEquals(BigDecimal.ZERO, repositoryDto.wholesalePrice);
        assertEquals(BigDecimal.ZERO, repositoryDto.wholesaleSalesPrice);
    }

    @Test
    void getProductsOnSale_shouldReturnSaleVariantDtoList()
    {
        PageRequest pageRequest = new PageRequest();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        ProductEntity productEntity = new ProductEntity();
        productEntity.id = productId;
        productEntity.name = "Promo Lamp";

        ProductVariantEntity variantEntity = new ProductVariantEntity();
        variantEntity.id = variantId;
        variantEntity.product = productEntity;
        variantEntity.sku = "PROMO-SKU";

        LocalDateTime saleStart = LocalDateTime.of(2026, 4, 1, 0, 0);
        LocalDateTime saleEnd = LocalDateTime.of(2026, 4, 30, 23, 59);
        VariantPricesEntity salePrice = new VariantPricesEntity();
        salePrice.priceType = PriceTypeEn.RETAIL_SALE_PRICE;
        salePrice.priceStartDate = saleStart;
        salePrice.priceEndDate = saleEnd;
        variantEntity.variantPrices = List.of(salePrice);

        ProductVariantDto variantDto = new ProductVariantDto();
        variantDto.id = variantId.toString();
        variantDto.sku = "PROMO-SKU";

        ProductDto productDto = new ProductDto();
        productDto.id = productId.toString();
        productDto.name = "Promo Lamp";

        ProductImageDto imageDto = new ProductImageDto("img-sale", "/images/promo-lamp.jpg", 1, true);

        when(productVariantRepository.findOnSaleVariants(pageRequest)).thenReturn(List.of(variantEntity));
        when(productMapper.mapVariantEntityToDto(variantEntity)).thenReturn(variantDto);
        when(productMapper.mapProductEntityToDto(productEntity)).thenReturn(productDto);
        when(productImageRepository.findByProductId(productId)).thenReturn(List.of());
        when(productMapper.mapImageEntitiesToDtos(List.of())).thenReturn(List.of(imageDto));

        List<SaleVariantDto> result = productService.getProductsOnSale(pageRequest);

        assertEquals(1, result.size());
        SaleVariantDto saleVariant = result.getFirst();
        assertSame(variantDto, saleVariant.variant);
        assertSame(productDto, saleVariant.product);
        assertEquals(List.of(imageDto), saleVariant.productImages);
        assertEquals(saleStart, saleVariant.variant.price_start_date);
        assertEquals(saleEnd, saleVariant.variant.price_end_date);

        verify(productVariantRepository).findOnSaleVariants(pageRequest);
        verify(productMapper).mapVariantEntityToDto(variantEntity);
        verify(productMapper).mapProductEntityToDto(productEntity);
        verify(productImageRepository).findByProductId(productId);
        verify(productMapper).mapImageEntitiesToDtos(List.of());
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
        List<ProductImageEntity> images = List.of();
        ProductInformationDto mappedDto = new ProductInformationDto();

        when(productRepository.findByIdWithCategoryAndBrand(productId)).thenReturn(product);
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(variants);
        when(productImageRepository.findByProductId(productId)).thenReturn(images);
        when(productMapper.mapToProductInformationDto(product, variants, images)).thenReturn(mappedDto);

        ProductInformationDto result = productService.getProductInformationDto(productId.toString());

        assertSame(mappedDto, result);
        verify(productRepository).findByIdWithCategoryAndBrand(productId);
        verify(productVariantRepository).findByVariantsForProductId(productId);
        verify(productImageRepository).findByProductId(productId);
        verify(productMapper).mapToProductInformationDto(product, variants, images);
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

