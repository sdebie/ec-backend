package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Type;

import java.util.List;

/**
 * DTO that represents a product with multiple variants and multiple images.
 */
@Type
public class ProductListDto {
    @Description("Product ID (UUID as string)")
    public String productId;

    @Description("Product name")
    public String productName;

    @Description("Product description")
    public String productDescription;

    @Description("List of product images")
    public List<ProductImageDto> productImages;

    @Description("List of variants for this product")
    public List<ProductVariantDto> variants;

    public ProductListDto() {}

    public ProductListDto(String productId, String productName, String productDescription,
                          List<ProductImageDto> productImages, List<ProductVariantDto> variants) {
        this.productId = productId;
        this.productName = productName;
        this.productDescription = productDescription;
        this.productImages = productImages;
        this.variants = variants;
    }
}
