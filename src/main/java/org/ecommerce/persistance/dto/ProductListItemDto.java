package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;

import java.util.List;

/**
 * Minimal DTO for listing products on the storefront.
 */
public class ProductListItemDto {
    @Description("Product ID (UUID as string)")
    public String id;

    @Description("Product name")
    public String name;

    @Description("Short description")
    public String description;

    @Description("Category Name")
    public String categoryName;

    @Description("Minimum variant price for the product")
    public Double price; // using Double for simplicity in GraphQL schema

    @Description("Featured image URL, if any")
    public String imageUrl;

    @Description("All variant IDs for this product")
    public List<String> variantIds;

    public ProductListItemDto() {}

    public ProductListItemDto(String id, String name, String description, Double price, String imageUrl, List<String> variantIds, String categoryName) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.variantIds = variantIds;
        this.categoryName = categoryName;
    }
}