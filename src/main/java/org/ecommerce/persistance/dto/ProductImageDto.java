package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Type;

/**
 * DTO representing a product image object (keeps object shape rather than plain URL strings).
 */
@Type
public class ProductImageDto {
    @Description("Image ID (UUID as string)")
    public String id;

    @Description("Image URL")
    public String imageUrl;

    @Description("Sort order for display")
    public Integer sortOrder;

    @Description("Whether this image is featured")
    public boolean isFeatured;

    public ProductImageDto() {}

    public ProductImageDto(String id, String imageUrl, Integer sortOrder, boolean isFeatured) {
        this.id = id;
        this.imageUrl = imageUrl;
        this.sortOrder = sortOrder;
        this.isFeatured = isFeatured;
    }
}
