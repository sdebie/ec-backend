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

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@GraphQLApi
public class ProductResource
{
    @Inject
    ProductService productService;

    @Query("productList")
    @Description("Returns a paged list of products with active retail or wholesale pricing. Supports categoryId and filterRequest.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryId") String categoryId)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            filters.add(new Filter("category.id", FilterOperator.EQUALS, categoryId));
            resolvedFilterRequest.setFilters(filters);
        }

        return productService.getAllProducts(pageRequest, resolvedFilterRequest);
    }

    @Query("shoppingProductList")
    @Description("Returns shopping product cards with variant count, image list, and active lowest prices by type. Supports categoryId and filterRequest.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getShoppingProductsList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryId") String categoryId)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        if (categoryId != null && !categoryId.isBlank() && !"ALL".equalsIgnoreCase(categoryId)) {
            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            filters.add(new Filter("category.id", FilterOperator.EQUALS, categoryId));
            resolvedFilterRequest.setFilters(filters);
        }

        return productService.getShoppingProducts(pageRequest, resolvedFilterRequest);
    }

    @Query("saleProductList")
    @Description("Returns products with variants that currently have active RETAIL_SALE_PRICE or WHOLESALE_SALE_PRICE values only.")
    @Transactional(value = TxType.SUPPORTS)
    public List<OnSaleProductListDto> getProductsOnSaleList(@Name("pageRequest") PageRequest pageRequest)
    {
        return productService.getProductsOnSale(pageRequest);
    }

    @Query("topBestSellers")
    @Description("Returns the top 10 best-selling products ranked by units sold in DELIVERED orders. " +
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
    @Description("Fetch a product with all variants for a given product id, including product images and prices for the selected category")
    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformation(@Name("productId") String productId) {
        return productService.getProductInformationDto(productId);
    }

    @Mutation("addProductInformation")
    @Description("Create a new product with variants and images")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(@Name("input") ProductInformationDto input) {
        return productService.addProductInformation(input);
    }

    @Mutation("updateProductInformation")
    @Description("Update an existing product with variants and images")
    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(
            @Name("productId") String productId,
            @Name("input") ProductInformationDto input) {
        return productService.updateProductInformation(productId, input);
    }


}
