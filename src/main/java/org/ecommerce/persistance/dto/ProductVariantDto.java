package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Type;

import java.math.BigDecimal;

/**
 * DTO that represents a single product variant (variant-specific information only).
 */
@Type
public class ProductVariantDto {
    @Description("Variant ID (UUID as string)")
    public String id;

    @Description("SKU of the variant")
    public String sku;

    @Description("Price of the variant")
    public BigDecimal price;

    @Description("Stock quantity available")
    public Integer stockQuantity;

    @Description("Attributes as JSON string")
    public String attributesJson;

    @Description("Weight in kilograms")
    public BigDecimal weightKg;

    public ProductVariantDto() {}

    public ProductVariantDto(String id, String sku, BigDecimal price, Integer stockQuantity,
                             String attributesJson, BigDecimal weightKg) {
        this.id = id;
        this.sku = sku;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.attributesJson = attributesJson;
        this.weightKg = weightKg;
    }
}
