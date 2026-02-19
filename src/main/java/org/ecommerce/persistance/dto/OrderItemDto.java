package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Input;

import java.math.BigDecimal;

@Input("OrderItemDtoInput")
@Description("Input item for an order line. Name is the product name sent by client.")
public class OrderItemDto {
    private BigDecimal unitPrice; // price per unit
    private Integer quantity;     // quantity of units
    private String name;          // product name (informational)

    @Description("Selected product variant ID")
    private Long variantId;

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
}
