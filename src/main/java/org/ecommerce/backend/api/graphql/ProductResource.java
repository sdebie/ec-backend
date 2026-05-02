package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.CategoryRepository;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class ProductResource
{
    @Inject
    ProductService productService;

    @Inject
    CategoryRepository categoryRepository;

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

        return productService.getProductsByCategory(categoryId, includeSubCategories, pageRequest, filterRequest);
    }

    @Query("shoppingProductList")
    @Description("Returns shopping product cards with variant count, image list, and active lowest prices by type. Products can belong to multiple categories. Supports categoryId and filterRequest.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getShoppingProductsList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryId") @Description("Optional category UUID to filter products. Returns products that belong to this category (products can belong to multiple categories).") String categoryId)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            filters.add(new Filter("c.id", FilterOperator.EQUALS, categoryId));
            resolvedFilterRequest.setFilters(filters);
        }

        return productService.getShoppingProducts(pageRequest, resolvedFilterRequest);
    }

    @Query("saleProductList")
    @Description("Returns shopping product cards that currently have active RETAIL_SALE_PRICE or WHOLESALE_SALE_PRICE values only.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSaleList(@Name("pageRequest") PageRequest pageRequest)
    {
        return productService.getProductsOnSale(pageRequest);
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
    @Description("Returns the total number of products matching the given filter.")
    @Transactional(value = TxType.SUPPORTS)
    public long productCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        return productService.productCount(filterRequest);
    }

    @Query("variantsByIds")
    @Description("Fetch product variants by a list of ids, including product relation and prices for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> variantsByIds(@Name("ids") List<String> ids) {
        return productService.getVariantsByIds(ids);
    }

    @Query("getProductInformation")
    @Description("Fetch a product with all variants, categories, and images for a given product id. Products can belong to multiple categories which are returned in the response.")
    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformation(@Name("productId") String productId) {
        return productService.getProductInformationDto(productId);
    }

    @Mutation("addProductInformation")
    @Description("Create a new product with variants, images, and multiple categories. Products can be assigned to one or more categories.")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(@Name("input") ProductInformationDto input) {
        return productService.addProductInformation(input);
    }

    @Mutation("updateProductInformation")
    @Description("Update an existing product with variants, images, and multiple categories. When updating categories, all previous category assignments are replaced with the new ones provided.")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(
            @Name("productId") String productId,
            @Name("input") ProductInformationDto input) {
        return productService.updateProductInformation(productId, input);
    }


}
