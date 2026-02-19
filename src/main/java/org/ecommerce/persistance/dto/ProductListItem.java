package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;

import java.util.List;

/**
 * Minimal DTO for listing products on the storefront.
 */
public class ProductListItem {
    @Description("Product ID")
    public Long id;

    @Description("Product name")
    public String name;

    @Description("Short description")
    public String description;

    @Description("Minimum variant price for the product")
    public Double price; // using Double for simplicity in GraphQL schema

    @Description("Featured image URL, if any")
    public String imageUrl;

    @Description("All variant IDs for this product")
    public List<Long> variantIds;

    public ProductListItem() {}

    public ProductListItem(Long id, String name, String description, Double price, String imageUrl, List<Long> variantIds) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.variantIds = variantIds;
    }
}