package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.dto.OnSaleProductListDto;
import org.ecommerce.common.dto.VariantPriceDto;
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

import java.time.LocalDateTime;
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
    void getProductsOnSale_shouldReturnSalesProductListWithLatestActiveListingPrices()
    {
        PageRequest pageRequest = new PageRequest();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        ProductEntity productEntity = new ProductEntity();
        productEntity.id = productId;
        productEntity.name = "Promo Lamp";

        ProductVariantEntity variantEntity = new ProductVariantEntity();
        variantEntity.id = variantId;
        variantEntity.product = productEntity;
        variantEntity.sku = "PROMO-SKU";

        VariantPricesEntity retailSalePriceEntity = new VariantPricesEntity();
        retailSalePriceEntity.id = UUID.randomUUID();
        retailSalePriceEntity.priceType = PriceTypeEn.RETAIL_SALE_PRICE;
        retailSalePriceEntity.price = java.math.BigDecimal.valueOf(99.99);
        retailSalePriceEntity.priceStartDate = now.minusDays(1);
        retailSalePriceEntity.priceEndDate = now.plusDays(5);

        VariantPricesEntity retailBasePriceEntity = new VariantPricesEntity();
        retailBasePriceEntity.id = UUID.randomUUID();
        retailBasePriceEntity.priceType = PriceTypeEn.RETAIL_PRICE;
        retailBasePriceEntity.price = java.math.BigDecimal.valueOf(149.99);
        retailBasePriceEntity.priceStartDate = now.minusDays(2);
        retailBasePriceEntity.priceEndDate = now.plusDays(10);

        variantEntity.prices = List.of(retailSalePriceEntity, retailBasePriceEntity);


        ProductVariantDto variantDto = new ProductVariantDto();
        variantDto.id = variantId.toString();
        variantDto.sku = "PROMO-SKU";

        VariantPriceDto retailSalePrice = new VariantPriceDto();
        retailSalePrice.id = UUID.randomUUID().toString();
        retailSalePrice.priceType = "RETAIL_SALE_PRICE";
        retailSalePrice.price = java.math.BigDecimal.valueOf(99.99);
        retailSalePrice.priceStartDate = now.minusDays(1);
        retailSalePrice.priceEndDate = now.plusDays(5);

        VariantPriceDto retailBasePrice = new VariantPriceDto();
        retailBasePrice.id = UUID.randomUUID().toString();
        retailBasePrice.priceType = "RETAIL_PRICE";
        retailBasePrice.price = java.math.BigDecimal.valueOf(149.99);
        retailBasePrice.priceStartDate = now.minusDays(2);
        retailBasePrice.priceEndDate = now.plusDays(10);

        ProductDto productDto = new ProductDto();
        productDto.id = productId.toString();
        productDto.name = "Promo Lamp";

        when(productVariantRepository.findOnSaleVariants(pageRequest)).thenReturn(List.of(variantEntity));
        when(productMapper.mapVariantEntityToDto(variantEntity)).thenReturn(variantDto);
        when(productMapper.mapProductEntityToDto(productEntity)).thenReturn(productDto);
        when(productMapper.mapPriceEntityToDto(retailSalePriceEntity)).thenReturn(retailSalePrice);
        when(productMapper.mapPriceEntityToDto(retailBasePriceEntity)).thenReturn(retailBasePrice);

        List<OnSaleProductListDto> result = productService.getProductsOnSale(pageRequest);

        assertEquals(1, result.size());
        OnSaleProductListDto salesProduct = result.getFirst();
        assertSame(productDto, salesProduct.product);
        assertEquals(1, salesProduct.variants.size());
        ProductVariantDto saleVariant = salesProduct.variants.getFirst();
        assertSame(variantDto, saleVariant);
        assertEquals(2, saleVariant.prices.size());
        assertEquals(List.of("RETAIL_PRICE", "RETAIL_SALE_PRICE"),
                saleVariant.prices.stream().map(price -> price.priceType).sorted().toList());

        verify(productVariantRepository).findOnSaleVariants(pageRequest);
        verify(productMapper).mapVariantEntityToDto(variantEntity);
        verify(productMapper).mapProductEntityToDto(productEntity);
        verify(productMapper).mapPriceEntityToDto(retailSalePriceEntity);
        verify(productMapper).mapPriceEntityToDto(retailBasePriceEntity);
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
