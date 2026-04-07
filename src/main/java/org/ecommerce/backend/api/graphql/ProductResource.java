package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.ProductService;
import org.ecommerce.common.dto.ProductInformationDto;
import org.ecommerce.common.dto.ProductListItemDto;
import org.ecommerce.common.dto.ProductVariantDto;
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
    @Description("Returns a paged list of products with price and sales price. Supports legacy categoryName and filterRequest.")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsList(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("categoryName") String categoryName)
    {
        FilterRequest resolvedFilterRequest = filterRequest != null ? filterRequest : new FilterRequest();

        // Backward compatibility: if categoryName is provided, apply it as category.name = :categoryName.
        if (categoryName != null && !categoryName.isBlank() && !"ALL".equalsIgnoreCase(categoryName)) {
            List<Filter> filters = resolvedFilterRequest.getFilters() != null
                    ? resolvedFilterRequest.getFilters()
                    : new ArrayList<>();
            filters.add(new Filter("category.name", FilterOperator.EQUALS, categoryName));
            resolvedFilterRequest.setFilters(filters);
        }

        return productService.getAllProducts(pageRequest, resolvedFilterRequest);
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
}
