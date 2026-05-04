package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.BrandRepository;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        repositoryDto.id = productId.toString();
        repositoryDto.name = "Desk Lamp";
        repositoryDto.description = "Warm light";
        repositoryDto.imageName = null;
        repositoryDto.variantIds = List.of();
        repositoryDto.categoryNames = List.of("Lighting");
        repositoryDto.brandName = "BrightCo";

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
        assertEquals(List.of("Lighting"), repositoryDto.categoryNames);
        assertEquals("BrightCo", repositoryDto.brandName);

        verify(productRepository).findAllProductListItems(pageRequest, filterRequest);
    }

    @Test
    void getAllProducts_shouldDefaultDtoWhenRepositoryReturnsNullId()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.id = null;
        repositoryDto.name = "Draft Product";
        repositoryDto.description = "No persisted id yet";
        repositoryDto.imageName = null;
        repositoryDto.variantIds = List.of();
        repositoryDto.categoryNames = List.of();
        repositoryDto.brandName = null;

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

    @Test
    void getProductsByCategory_shouldRequireExistingCategory()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID categoryId = UUID.randomUUID();

        when(categoryRepository.findById(categoryId)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getProductsByCategory(categoryId.toString(), true, pageRequest, filterRequest)
        );

        assertEquals("Category not found with id: " + categoryId, ex.getMessage());
    }

    @Test
    void getProductsByCategory_shouldLoadProductsForMainCategoryOnlyWhenSubcategoriesDisabled()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID categoryId = UUID.randomUUID();

        CategoryEntity rootCategory = new CategoryEntity();
        rootCategory.id = categoryId;

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.id = null;
        repositoryDto.name = "Main Category Product";

        when(categoryRepository.findById(categoryId)).thenReturn(rootCategory);
        when(productRepository.findProductListItemsByCategoryIds(pageRequest, filterRequest, List.of(categoryId)))
                .thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByCategory(categoryId.toString(), false, pageRequest, filterRequest);

        assertEquals(1, result.size());
        verify(productRepository).findProductListItemsByCategoryIds(pageRequest, filterRequest, List.of(categoryId));
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
        parentCategory.id = parentCategoryId;

        CategoryEntity selectedCategory = new CategoryEntity();
        selectedCategory.id = selectedCategoryId;
        selectedCategory.parent = parentCategory;

        CategoryEntity siblingCategory = new CategoryEntity();
        siblingCategory.id = siblingCategoryId;
        siblingCategory.parent = parentCategory;

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.id = null;
        repositoryDto.name = "Parent Scope Product";

        when(categoryRepository.findById(selectedCategoryId)).thenReturn(selectedCategory);
        when(categoryRepository.list("parent.id", parentCategoryId)).thenReturn(List.of(selectedCategory, siblingCategory));
        when(productRepository.findProductListItemsByCategoryIds(pageRequest, filterRequest, List.of(selectedCategoryId, siblingCategoryId)))
                .thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByCategory(selectedCategoryId.toString(), true, pageRequest, filterRequest);

        assertEquals(1, result.size());
        verify(productRepository).findProductListItemsByCategoryIds(pageRequest, filterRequest, List.of(selectedCategoryId, siblingCategoryId));
    }

    @Test
    void getProductsByBrand_shouldRequireExistingBrand()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        UUID brandId = UUID.randomUUID();

        when(brandRepository.findById(brandId)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> productService.getProductsByBrand(brandId.toString(), pageRequest, filterRequest)
        );

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
        brand.id = brandId;

        ProductListItemDto repositoryDto = new ProductListItemDto();
        repositoryDto.id = null;
        repositoryDto.name = "Mask Product";

        when(brandRepository.findById(brandId)).thenReturn(brand);
        when(productRepository.findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), org.mockito.ArgumentMatchers.any(FilterRequest.class)))
                .thenReturn(List.of(repositoryDto));

        List<ProductListItemDto> result = productService.getProductsByBrand(brandId.toString(), pageRequest, filterRequest);

        assertEquals(1, result.size());

        ArgumentCaptor<FilterRequest> filterCaptor = ArgumentCaptor.forClass(FilterRequest.class);
        verify(productRepository).findAllProductListItems(org.mockito.ArgumentMatchers.eq(pageRequest), filterCaptor.capture());

        List<Filter> sentFilters = filterCaptor.getValue().getFilters();
        assertEquals(2, sentFilters.size());
        assertEquals("name", sentFilters.get(0).getKey());
        assertEquals("brand.id", sentFilters.get(1).getKey());
        assertEquals(FilterOperator.EQUALS, sentFilters.get(1).getOperator());
        assertEquals(brandId.toString(), sentFilters.get(1).getValue());
    }
}
