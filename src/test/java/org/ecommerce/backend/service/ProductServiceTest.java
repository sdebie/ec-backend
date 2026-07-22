package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    ProductImageRepository productImageRepository;

    @InjectMock
    ProductMapper productMapper;

    @InjectMock
    CategoryRepository categoryRepository;

    @InjectMock
    BrandRepository brandRepository;

    @Test
    void getAllProducts_shouldEnrichRepositoryDtosWithoutUsingEntitiesInService()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID productId = UUID.randomUUID();

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(productId.toString());
        repositoryDto.setName("Desk Lamp");
        repositoryDto.setDescription("Warm light");
        repositoryDto.setImageName(null);
        repositoryDto.setVariantIds(List.of());
        repositoryDto.setCategoryNames(List.of("Lighting"));
        repositoryDto.setBrandName("BrightCo");

        ProductVariantEntity variant1 = new ProductVariantEntity();
        variant1.setId(UUID.randomUUID());
        ProductVariantEntity variant2 = new ProductVariantEntity();
        variant2.setId(UUID.randomUUID());

        ProductImageEntity featuredImage = new ProductImageEntity();
        featuredImage.setImageUrl("/images/lamp.jpg");
        featuredImage.setIsFeatured(true);

        when(productRepository.findAllProductListItems(pageRequest, filterRequest, true)).thenReturn(List.of(repositoryDto));
        when(productVariantRepository.findByVariantsForProductId(productId)).thenReturn(List.of(variant1, variant2));
        when(productImageRepository.findFeaturedByProductId(productId)).thenReturn(featuredImage);

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(variant1.getId().toString(), variant2.getId().toString()), repositoryDto.getVariantIds());
        assertEquals("/images/lamp.jpg", repositoryDto.getImageName());
        assertEquals(List.of("Lighting"), repositoryDto.getCategoryNames());
        assertEquals("BrightCo", repositoryDto.getBrandName());

        verify(productRepository).findAllProductListItems(pageRequest, filterRequest, true);
    }

    @Test
    void getAllProducts_shouldDefaultDtoWhenRepositoryReturnsNullId()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(null);
        repositoryDto.setName("Draft Product");
        repositoryDto.setDescription("No persisted id yet");
        repositoryDto.setImageName(null);
        repositoryDto.setVariantIds(List.of());
        repositoryDto.setCategoryNames(List.of());
        repositoryDto.setBrandName(null);

        when(productRepository.findAllProductListItems(pageRequest, filterRequest, true)).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getAllProducts(pageRequest, filterRequest);

        assertEquals(1, result.size());
        assertSame(repositoryDto, result.getFirst());
        assertEquals(List.of(), repositoryDto.getVariantIds());
        assertNull(repositoryDto.getImageName());
        assertNull(repositoryDto.getBrandName());
    }


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

        List<ProductShoppingListItemDto> result = productService.getProductsOnSale(pageRequest, false);

        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(second, result.get(1));

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

    @Test
    void getProductsByCategory_shouldRequireExistingCategory()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID categoryId = UUID.randomUUID();

        when(categoryRepository.findById(categoryId)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> productService.getProductsByCategory(categoryId.toString(), true, pageRequest, filterRequest, false));

        assertEquals("Category not found with id: " + categoryId, ex.getMessage());
    }

    @Test
    void getProductsByCategory_shouldLoadProductsForMainCategoryOnlyWhenSubcategoriesDisabled()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID categoryId = UUID.randomUUID();

        CategoryEntity rootCategory = new CategoryEntity();
        rootCategory.setId(categoryId);

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(null);
        repositoryDto.setName("Main Category Product");

        when(categoryRepository.findById(categoryId)).thenReturn(rootCategory);
        when(productRepository.findProductListItemsByCategoryIds(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class), org.mockito.ArgumentMatchers.eq(List.of(categoryId)), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByCategory(categoryId.toString(), false, pageRequest, filterRequest, false);

        assertEquals(1, result.size());

        ArgumentCaptor<FilterRequest> filterCaptor = ArgumentCaptor.forClass(FilterRequest.class);
        verify(productRepository).findProductListItemsByCategoryIds(org.mockito.ArgumentMatchers.eq(pageRequest), filterCaptor.capture(), org.mockito.ArgumentMatchers.eq(List.of(categoryId)), org.mockito.ArgumentMatchers.eq(false));
        List<Filter> sentFilters = filterCaptor.getValue().getFilters();
        assertEquals(1, sentFilters.size());
        assertEquals("status", sentFilters.getFirst().getKey());
        assertEquals(FilterOperator.EQUALS, sentFilters.getFirst().getOperator());
        assertEquals(ProductStatusEn.ACTIVE.name(), sentFilters.getFirst().getValue());
    }

    @Test
    void getProductsByCategory_shouldLoadSelectedAndParentScopeCategoriesWhenSubcategoriesEnabled()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();

        UUID parentCategoryId = UUID.randomUUID();
        UUID selectedCategoryId = UUID.randomUUID();
        UUID siblingCategoryId = UUID.randomUUID();

        CategoryEntity parentCategory = new CategoryEntity();
        parentCategory.setId(parentCategoryId);

        CategoryEntity selectedCategory = new CategoryEntity();
        selectedCategory.setId(selectedCategoryId);
        selectedCategory.setParent(parentCategory);

        CategoryEntity siblingCategory = new CategoryEntity();
        siblingCategory.setId(siblingCategoryId);
        siblingCategory.setParent(parentCategory);

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(null);
        repositoryDto.setName("Parent Scope Product");

        when(categoryRepository.findById(selectedCategoryId)).thenReturn(selectedCategory);
        when(categoryRepository.list("parent.id", parentCategoryId)).thenReturn(List.of(selectedCategory, siblingCategory));
        when(productRepository.findProductListItemsByCategoryIds(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class), org.mockito.ArgumentMatchers.eq(List.of(selectedCategoryId, siblingCategoryId)), org.mockito.ArgumentMatchers.eq(true))).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByCategory(selectedCategoryId.toString(), true, pageRequest, filterRequest, true);

        assertEquals(1, result.size());
        verify(productRepository).findProductListItemsByCategoryIds(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class), org.mockito.ArgumentMatchers.eq(List.of(selectedCategoryId, siblingCategoryId)), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void getProductsByBrand_shouldRequireExistingBrand()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID brandId = UUID.randomUUID();

        when(brandRepository.findById(brandId)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> productService.getProductsByBrand(brandId.toString(), pageRequest, filterRequest, false));

        assertEquals("Brand not found with id: " + brandId, ex.getMessage());
    }

    @Test
    void getProductsByBrand_shouldAppendBrandFilterAndReturnPagedList()
    {
        PageRequest pageRequest = new PageRequest();
        UUID brandId = UUID.randomUUID();

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, "mask")));

        BrandEntity brand = new BrandEntity();
        brand.setId(brandId);

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(null);
        repositoryDto.setName("Mask Product");

        when(brandRepository.findById(brandId)).thenReturn(brand);
        when(productRepository.findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class), org.mockito.ArgumentMatchers.eq(false))).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByBrand(brandId.toString(), pageRequest, filterRequest, false);

        assertEquals(1, result.size());

        ArgumentCaptor<FilterRequest> filterCaptor = ArgumentCaptor.forClass(FilterRequest.class);
        verify(productRepository).findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), filterCaptor.capture(), org.mockito.ArgumentMatchers.eq(false));

        List<Filter> sentFilters = filterCaptor.getValue().getFilters();
        assertEquals(3, sentFilters.size());
        assertEquals("name", sentFilters.get(0).getKey());
        assertEquals("status", sentFilters.get(1).getKey());
        assertEquals(FilterOperator.EQUALS, sentFilters.get(1).getOperator());
        assertEquals(ProductStatusEn.ACTIVE.name(), sentFilters.get(1).getValue());
        assertEquals("brand.id", sentFilters.get(2).getKey());
        assertEquals(FilterOperator.EQUALS, sentFilters.get(2).getOperator());
        assertEquals(brandId.toString(), sentFilters.get(2).getValue());
    }

    @Test
    void getProductsByBrand_shouldSkipStatusFilterWhenIgnoreStatusTrue()
    {
        PageRequest pageRequest = new PageRequest();
        UUID brandId = UUID.randomUUID();

        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("name", FilterOperator.ILIKE, "mask")));

        BrandEntity brand = new BrandEntity();
        brand.setId(brandId);

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.setId(null);
        repositoryDto.setName("Mask Product");

        when(brandRepository.findById(brandId)).thenReturn(brand);
        when(productRepository.findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class), org.mockito.ArgumentMatchers.eq(true))).thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByBrand(brandId.toString(), pageRequest, filterRequest, true);

        assertEquals(1, result.size());

        ArgumentCaptor<FilterRequest> filterCaptor = ArgumentCaptor.forClass(FilterRequest.class);
        verify(productRepository).findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), filterCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));

        List<Filter> sentFilters = filterCaptor.getValue().getFilters();
        assertEquals(2, sentFilters.size());
        assertEquals("name", sentFilters.get(0).getKey());
        assertEquals("brand.id", sentFilters.get(1).getKey());
    }
}
