package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.FeaturedProductService;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.BrandRepository;
import org.ecommerce.common.repository.CategoryRepository;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class ProductResource
{
    @Inject
    ProductService productService;

    @Inject
    FeaturedProductService featuredProductService;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    BrandRepository brandRepository;

    @Query("productList")
    @Description("Returns a paged list of products with active retail or wholesale pricing. Category scoping is not applied in this endpoint.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest)
    {
        return productService.getAllProducts(pageRequest, filterRequest);
    }

    @Query("productListByCategory")
    @Description("Returns a paged list of products for a mandatory category. Optionally includes categories under the same parent scope.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsListByCategory(
            @Name("categoryId") @Description("Required category UUID.") String categoryId,
            @Name("includeSubCategories") @DefaultValue("true") @Description("When true, products in the selected category and related parent-scope categories are included.") boolean includeSubCategories,
            @Name("ignoreStatus") @DefaultValue("false") @Description("When true, skips the default ACTIVE product and variant status restriction.") boolean ignoreStatus,
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest)
    {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId is required and must reference a main category");
        }

        final UUID parsedCategoryId;
        try {
            parsedCategoryId = UUID.fromString(categoryId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("categoryId must be a valid UUID for a main category", e);
        }

        if (categoryRepository.findById(parsedCategoryId) == null) {
            throw new IllegalArgumentException("Category not found for id: " + categoryId);
        }

        return productService.getProductsByCategory(categoryId, includeSubCategories, pageRequest, filterRequest, ignoreStatus);
    }

    @Query("productListByBrand")
    @Description("Returns a paged list of products linked to a mandatory brand.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsListByBrand(
            @Name("brandId") @Description("Required brand UUID.") String brandId,
            @Name("ignoreStatus") @DefaultValue("false") @Description("When true, skips the default ACTIVE product and variant status restriction.") boolean ignoreStatus,
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest)
    {
        if (brandId == null || brandId.isBlank()) {
            throw new IllegalArgumentException("brandId is required and must reference a brand");
        }

        final UUID parsedBrandId;
        try {
            parsedBrandId = UUID.fromString(brandId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("brandId must be a valid UUID for a brand", e);
        }

        if (brandRepository.findById(parsedBrandId) == null) {
            throw new IllegalArgumentException("Brand not found for id: " + brandId);
        }

        return productService.getProductsByBrand(brandId, pageRequest, filterRequest, ignoreStatus);
    }

    @Query("shoppingProductList")
    @Description("Returns paginated shopping product cards with variant count, image list, and active lowest prices by type. Products can belong to multiple categories. Supports optional category scope and includeSubCategories.")
    @Transactional(value = TxType.SUPPORTS)
    public PageResponse<ProductShoppingListItemDto> getShoppingProductsList(
            @Name("pageIndex") @DefaultValue("0") int pageIndex,
            @Name("pageSize") @DefaultValue("20") int pageSize,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryId") @Description("Optional main category UUID. If omitted or ALL, returns products across all categories.") String categoryId,
            @Name("onSale") @DefaultValue("false") boolean onSale,
            @Name("ignoreStatus") @DefaultValue("false") @Description("When true, skips the default ACTIVE product and variant status restriction.") boolean ignoreStatus,
            @Name("includeSubCategories") @DefaultValue("true") @Description("When true, includes products linked to the selected category and all descendant categories. When false, only products linked directly to the selected category are returned.") boolean includeSubCategories)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            final UUID parsedCategoryId;
            try {
                parsedCategoryId = UUID.fromString(categoryId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("categoryId must be a valid UUID", e);
            }

            if (categoryRepository.findById(parsedCategoryId) == null) {
                throw new IllegalArgumentException("Category not found for id: " + categoryId);
            }

            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();

            if (includeSubCategories) {
                List<String> scopedCategoryIds = resolveCategoryAndDescendantIds(parsedCategoryId)
                        .stream()
                        .map(UUID::toString)
                        .toList();
                filters.add(new Filter("category.id", FilterOperator.IN, scopedCategoryIds));
            } else {
                filters.add(new Filter("category.id", FilterOperator.EQUALS, categoryId));
            }

            resolvedFilterRequest.setFilters(filters);
        }

        int effectivePageSize = Math.min(pageSize, 50);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageIndex(pageIndex);
        pageRequest.setPageSize(effectivePageSize);

        List<ProductShoppingListItemDto> content = productService.getShoppingProducts(pageRequest, resolvedFilterRequest, onSale, ignoreStatus);
        long totalElements = productService.countShoppingProducts(resolvedFilterRequest, onSale, ignoreStatus);
        int totalPages = (int) Math.ceil((double) totalElements / effectivePageSize);

        return new PageResponse<>(content, totalElements, totalPages, pageIndex, effectivePageSize);
    }

    private List<UUID> resolveCategoryAndDescendantIds(UUID rootCategoryId)
    {
        Set<UUID> collectedIds = new LinkedHashSet<>();
        List<UUID> frontier = new ArrayList<>();
        frontier.add(rootCategoryId);

        while (!frontier.isEmpty()) {
            List<UUID> nextFrontier = new ArrayList<>();
            for (UUID currentId : frontier) {
                if (!collectedIds.add(currentId)) {
                    continue;
                }

                List<org.ecommerce.common.entity.CategoryEntity> children = categoryRepository.list("parent.id", currentId);
                for (org.ecommerce.common.entity.CategoryEntity child : children) {
                    if (child != null && child.id != null && !collectedIds.contains(child.id)) {
                        nextFrontier.add(child.id);
                    }
                }
            }
            frontier = nextFrontier;
        }

        return new ArrayList<>(collectedIds);
    }

    @Query("saleProductList")
    @Description("Returns shopping product cards that currently have active RETAIL_SALE_PRICE or WHOLESALE_SALE_PRICE values only.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSaleList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("ignoreStatus") @DefaultValue("false") @Description("When true, skips the default ACTIVE product and variant status restriction.") boolean ignoreStatus)
    {
        return productService.getProductsOnSale(pageRequest, ignoreStatus);
    }

    @Query("topBestSellers")
    @Description("Returns the top 10 best-selling products ranked by units sold in DELIVERED orders. Products can belong to multiple categories. " +
            "If fewer than 10 delivered-order products exist, the list is padded with random products.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getTopBestSellers()
    {
        return productService.getTopBestSellers();
    }

    @Query("productCount")
    @Description("Returns the total number of products matching the given filter, optionally scoped to a category (including subcategories) and/or a brand.")
    @Transactional(value = TxType.SUPPORTS)
    public long productCount(
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryId") @Description("Optional category UUID. Includes products in this category and all subcategories. Pass null or omit for all categories.") String categoryId,
            @Name("brandId") @Description("Optional brand UUID. Restricts count to products linked to this brand.") String brandId,
            @Name("ignoreStatus") @DefaultValue("false") @Description("When true, skips the default ACTIVE product and variant status restriction.") boolean ignoreStatus)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            final UUID parsedCategoryId;
            try {
                parsedCategoryId = UUID.fromString(categoryId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("categoryId must be a valid UUID", e);
            }

            if (categoryRepository.findById(parsedCategoryId) == null) {
                throw new IllegalArgumentException("Category not found for id: " + categoryId);
            }

            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            List<String> scopedCategoryIds = resolveCategoryAndDescendantIds(parsedCategoryId)
                    .stream()
                    .map(UUID::toString)
                    .toList();
            filters.add(new Filter("category.id", FilterOperator.IN, scopedCategoryIds));
            resolvedFilterRequest.setFilters(filters);
        }

        if (brandId != null && !brandId.isBlank()) {
            final UUID parsedBrandId;
            try {
                parsedBrandId = UUID.fromString(brandId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("brandId must be a valid UUID", e);
            }

            if (brandRepository.findById(parsedBrandId) == null) {
                throw new IllegalArgumentException("Brand not found for id: " + brandId);
            }

            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            filters.add(new Filter("brand.id", FilterOperator.EQUALS, brandId));
            resolvedFilterRequest.setFilters(filters);
        }

        boolean isScoped = (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId))
                || (brandId != null && !brandId.isBlank());

        return isScoped
                ? productService.countShoppingProducts(resolvedFilterRequest, false, ignoreStatus)
                : productService.productCount(resolvedFilterRequest, ignoreStatus);
    }

    @Query("variantsByIds")
    @Description("Fetch product variants by a list of ids, including product relation and prices for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> variantsByIds(@Name("ids") List<String> ids)
    {
        return productService.getVariantsByIds(ids);
    }

    @Query("getProductInformation")
    @Description("Fetch a product with all variants, categories, and images for a given product id. Products can belong to multiple categories which are returned in the response.")
    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformation(@Name("productId") String productId)
    {
        return productService.getProductInformationDto(productId);
    }

    @Mutation("addProductInformation")
    @Description("Create a new product with variants, images, and multiple categories. Products can be assigned to one or more categories.")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(@Name("input") ProductInformationDto input)
    {
        return productService.addProductInformation(input);
    }

    @Mutation("updateProductInformation")
    @Description("Update an existing product with variants, images, and multiple categories. When updating categories, all previous category assignments are replaced with the new ones provided.")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(
            @Name("productId") String productId,
            @Name("input") ProductInformationDto input)
    {
        return productService.updateProductInformation(productId, input);
    }

    @Query("getProductInformationBySlug")
    @Description("Fetch a product with all variants by slug.")
    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformationBySlug(@Name("slug") String slug)
    {
        return productService.getProductInformationBySlug(slug);
    }

    @Query("adminProductList")
    @Description("Admin paginated product list with SKU, stock, and price. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER"})
    @Transactional(value = TxType.SUPPORTS)
    public PageResponse<AdminProductListItemDto> adminProductList(
            @Name("pageIndex") @DefaultValue("0") int pageIndex,
            @Name("pageSize") @DefaultValue("10") int pageSize,
            @Name("status") String status,
            @Name("categoryId") String categoryId,
            @Name("brandId") String brandId,
            @Name("search") String search)
    {
        return productService.getAdminProductList(pageIndex, pageSize, status, categoryId, brandId, search);
    }

    @Query("adminProductStats")
    @Description("Product count breakdown by status. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER", "ORDER_MANAGER", "VIEWER"})
    @Transactional(value = TxType.SUPPORTS)
    public AdminProductStatsDto adminProductStats()
    {
        return productService.getProductStats();
    }

    @Mutation("updateProductStatus")
    @Description("Update a product's status. SUPER_ADMIN only.")
    @RolesAllowed("SUPER_ADMIN")
    @Transactional(value = TxType.REQUIRED)
    public void updateProductStatus(
            @Name("id") String id,
            @Name("status") String status)
    {
        productService.updateProductStatus(id, status);
    }

    @Mutation("deleteProduct")
    @Description("Delete a product and all its variants. SUPER_ADMIN only.")
    @RolesAllowed("SUPER_ADMIN")
    @Transactional(value = TxType.REQUIRED)
    public void deleteProduct(@Name("id") String id)
    {
        productService.deleteProduct(id);
    }

    @Query("featuredProductList")
    @Description("Returns all featured products for admin management, ordered by name ascending.")
    @RolesAllowed({"SUPER_ADMIN", "VIEWER"})
    @Transactional(value = TxType.SUPPORTS)
    public List<AdminProductListItemDto> featuredProductList()
    {
        return featuredProductService.getFeaturedProductsForAdmin();
    }

    @Mutation("setProductFeatured")
    @Description("Toggle the featured flag on a product. SUPER_ADMIN only.")
    @RolesAllowed("SUPER_ADMIN")
    @Transactional(value = TxType.REQUIRED)
    public FeaturedProductResultDto setProductFeatured(
            @Name("productId") String productId,
            @Name("featured") boolean featured)
    {
        return featuredProductService.setFeatured(UUID.fromString(productId), featured);
    }

    @Query("shoppingFeaturedProductList")
    @Description("Returns featured ACTIVE products as shopping DTOs for the storefront. Publicly accessible.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> shoppingFeaturedProductList(
            @Name("limit") @DefaultValue("8") int limit,
            @Name("categorySlug") String categorySlug)
    {
        return featuredProductService.getFeaturedShoppingProducts(limit, categorySlug);
    }
}
