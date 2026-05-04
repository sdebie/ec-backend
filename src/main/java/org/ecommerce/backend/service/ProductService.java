package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.BrandRepository;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class ProductService
{
    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    BrandRepository brandRepository;

    @Inject
    ProductMapper productMapper;

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getAllProducts(PageRequest pageRequest, FilterRequest filterRequest)
    {
        return enrichProductListItems(productRepository.findAllProductListItems(pageRequest, filterRequest));
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsByCategory(String categoryId, boolean includeSubCategories, PageRequest pageRequest, FilterRequest filterRequest)
    {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("Category id is required");
        }

        UUID selectedCategoryId = UUID.fromString(categoryId);
        CategoryEntity selectedCategory = categoryRepository.findById(selectedCategoryId);
        if (selectedCategory == null) {
            throw new IllegalArgumentException("Category not found with id: " + categoryId);
        }

        List<UUID> categoryIds = includeSubCategories
                ? resolveCategoryScopeIds(selectedCategory)
                : List.of(selectedCategoryId);

        return enrichProductListItems(
                productRepository.findProductListItemsByCategoryIds(pageRequest, filterRequest, categoryIds)
        );
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsByBrand(String brandId, PageRequest pageRequest, FilterRequest filterRequest)
    {
        if (brandId == null || brandId.isBlank()) {
            throw new IllegalArgumentException("Brand id is required");
        }

        final UUID parsedBrandId;
        try {
            parsedBrandId = UUID.fromString(brandId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Brand id must be a valid UUID", e);
        }

        if (brandRepository.findById(parsedBrandId) == null) {
            throw new IllegalArgumentException("Brand not found with id: " + brandId);
        }

        FilterRequest effectiveFilterRequest = new FilterRequest();
        effectiveFilterRequest.setSort(filterRequest != null ? filterRequest.getSort() : null);
        effectiveFilterRequest.setFilterGroups(filterRequest != null ? filterRequest.getFilterGroups() : null);

        List<Filter> filters = filterRequest != null && filterRequest.getFilters() != null
                ? new ArrayList<>(filterRequest.getFilters())
                : new ArrayList<>();
        filters.add(new Filter("brand.id", FilterOperator.EQUALS, brandId));
        effectiveFilterRequest.setFilters(filters);

        return enrichProductListItems(
                productRepository.findAllProductListItems(pageRequest, effectiveFilterRequest)
        );
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getShoppingProducts(PageRequest pageRequest, FilterRequest filterRequest)
    {
        return productRepository.findShoppingProductList(pageRequest, filterRequest);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSale(PageRequest pageRequest)
    {
        return productRepository.findOnSaleShoppingProductList(pageRequest);
    }



    private List<ProductListItemDto> enrichProductListItems(List<ProductListItemDto> products)
    {
        return products.stream().map(product -> {
            if (product.id == null) {
                product.variantIds = List.of();
                product.imageName = null;
                return product;
            }

            UUID productId = UUID.fromString(product.id);

            product.variantIds = productVariantRepository.findByVariantsForProductId(productId)
                    .stream()
                    .map(v -> v.id.toString())
                    .collect(Collectors.toList());

            ProductImageEntity featuredImage = productImageRepository.findFeaturedByProductId(productId);
            product.imageName = featuredImage != null ? featuredImage.imageUrl : null;

            return product;
        }).collect(Collectors.toList());
    }

    private List<UUID> resolveCategoryScopeIds(CategoryEntity selectedCategory)
    {
        Set<UUID> scopedIds = new LinkedHashSet<>();
        scopedIds.add(selectedCategory.id);

        UUID groupParentId = selectedCategory.parent != null ? selectedCategory.parent.id : selectedCategory.id;
        List<CategoryEntity> groupedCategories = categoryRepository.list("parent.id", groupParentId);
        for (CategoryEntity category : groupedCategories) {
            if (category != null && category.id != null) {
                scopedIds.add(category.id);
            }
        }

        return new ArrayList<>(scopedIds);
    }

    @Transactional(value = TxType.SUPPORTS)
    public long productCount(FilterRequest filterRequest)
    {
        return productRepository.count(filterRequest);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getTopBestSellers()
    {
        return productRepository.findTopBestSellers();
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> getVariantsByIds(List<String> ids)
    {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return productMapper.mapVariantEntitiesToDtos(
                productVariantRepository.findByIdsWithProduct(uuidIds));
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformationDto(String productId)
    {
        UUID pid = UUID.fromString(productId);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }

        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid));
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(ProductInformationDto input)
    {
        if (input == null) {
            log.error("ProductInformationDto is null");
            throw new IllegalArgumentException("Product information cannot be null");
        }

        // Create new product entity
        ProductEntity product = new ProductEntity();
        product.name = input.product.name;
        product.slug = input.product.slug;
        product.description = input.product.description;
        product.shorDescription = input.product.shortDescription;
        product.productType = input.product.productType != null ? ProductTypeEn.valueOf(input.product.productType) : ProductTypeEn.SIMPLE;

        // Link categories if provided
        if (input.product.categories != null && !input.product.categories.isEmpty()) {
            for (CategoryDto categoryDto : input.product.categories) {
                if (categoryDto.id != null) {
                    UUID categoryId = categoryDto.id;
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.categories.add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.product.category != null && input.product.category.id != null) {
            // Backward compatibility: handle single category
            UUID categoryId = input.product.category.id;
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.categories.add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Link brand if provided
        if (input.product.brand != null && input.product.brand.id != null) {
            UUID brandId = input.product.brand.id;
            product.brand = brandRepository.findById(brandId);
            if (product.brand != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save product
        product.persist();
        log.info("Created new product with ID: {}", product.id);

        // TODO: Handle product images and variants creation
        // This would require additional repository methods or separate transactions

        return productMapper.mapToProductInformationDto(
                product,
                List.of());
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(String productId, ProductInformationDto input)
    {
        if (input == null) {
            log.error("ProductInformationDto is null");
            throw new IllegalArgumentException("Product information cannot be null");
        }

        UUID pid = UUID.fromString(productId);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);

        if (product == null) {
            log.error("Product not found with ID: {}", productId);
            throw new IllegalArgumentException("Product not found");
        }

        // Update product information
        if (input.product.name != null && !input.product.name.isBlank()) {
            product.name = input.product.name;
        }
        if (input.product.slug != null && !input.product.slug.isBlank()) {
            product.slug = input.product.slug;
        }
        if (input.product.description != null && !input.product.description.isBlank()) {
            product.description = input.product.description;
        }
        if (input.product.shortDescription != null && !input.product.shortDescription.isBlank()) {
            product.shorDescription = input.product.shortDescription;
        }
        if (input.product.productType != null && !input.product.productType.isBlank()) {
            product.productType = ProductTypeEn.valueOf(input.product.productType);
        }

        // Update categories if provided
        if (input.product.categories != null && !input.product.categories.isEmpty()) {
            product.categories.clear();
            for (CategoryDto categoryDto : input.product.categories) {
                if (categoryDto.id != null) {
                    UUID categoryId = categoryDto.id;
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.categories.add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.product.category != null && input.product.category.id != null) {
            // Backward compatibility: handle single category
            product.categories.clear();
            UUID categoryId = input.product.category.id;
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.categories.add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Update brand if provided
        if (input.product.brand != null && input.product.brand.id != null) {
            UUID brandId = input.product.brand.id;
            product.brand = brandRepository.findById(brandId);
            if (product.brand != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save updated product
        product.persist();
        log.info("Updated product with ID: {}", product.id);

        // Handle product variants updates
        if (input.variants != null && !input.variants.isEmpty()) {
            updateProductVariants(pid, input.variants);
        }

        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid));
    }

    /**
     * Updates product images by replacing existing images with new ones
     */
    private void updateProductImages(UUID productId, List<ProductImageDto> newImages) {
        log.info("Updating images for product ID: {}", productId);

        // Delete all existing images for this product
        List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);
        for (ProductImageEntity image : existingImages) {
            image.delete();
        }

        // Note: ProductImageEntity requires a ProductVariantEntity relationship
        // Images are typically linked to variants, not directly to products
        // This is a limitation of the current schema - would need product variants to exist first
        // TODO: Once variants are created, associate images with the appropriate variant
    }

    /**
     * Updates product variants and their prices
     */
    private void updateProductVariants(UUID productId, List<ProductVariantDto> newVariants) {
        log.info("Updating variants for product ID: {}", productId);

        // Get existing variants for this product
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(productId);

        // Update existing variants or create new ones
        for (ProductVariantDto variantDto : newVariants) {
            ProductVariantEntity variant = null;

            // Check if variant with this SKU already exists
            if (variantDto.id != null && !variantDto.id.isBlank()) {
                variant = productVariantRepository.findByIdWithProduct(UUID.fromString(variantDto.id));
            } else if (variantDto.sku != null) {
                // Try to find by SKU
                variant = existingVariants.stream()
                        .filter(v -> v.sku.equals(variantDto.sku))
                        .findFirst()
                        .orElse(null);
            }

            if (variant == null) {
                // Create new variant
                variant = new ProductVariantEntity();
                variant.product = productRepository.findByIdWithCategoryAndBrand(productId);
                variant.sku = variantDto.sku;
                variant.stockQuantity = variantDto.stockQuantity;
                variant.attributesJson = variantDto.attributesJson;
                variant.weightKg = variantDto.weightKg;
                variant.persist();
                log.info("Created new variant with SKU: {}", variantDto.sku);
            } else {
                // Update existing variant
                if (variantDto.stockQuantity != null) {
                    variant.stockQuantity = variantDto.stockQuantity;
                }
                if (variantDto.attributesJson != null) {
                    variant.attributesJson = variantDto.attributesJson;
                }
                if (variantDto.weightKg != null) {
                    variant.weightKg = variantDto.weightKg;
                }
                variant.persist();
                log.info("Updated variant with SKU: {}", variantDto.sku);
            }
            //TODO:: Update Pricing
        }

        // Optionally delete variants not in the new list
        // For now, we'll keep this as a TODO to preserve existing data
        // TODO: Implement logic to delete variants not provided in the update
    }

}
