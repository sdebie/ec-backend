package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Input;
import org.ecommerce.persistance.entity.OrderItemEntity;

import java.math.BigDecimal;
import java.util.List;

@Input("OrderDtoInput")
@Description("Input type for creating/updating an order")
public class OrderDto {
    // Use wrapper type Long so GraphQL input can be nullable during create
    private Long orderId;

    // Prefer camelCase for GraphQL schema; also accept legacy 'total_amount' via @Name
    private BigDecimal totalAmount;

    private List<OrderItemEntity> items;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public List<OrderItemEntity> getItems() {
        return items;
    }

    public void setItems(List<OrderItemEntity> items) {
        this.items = items;
    }
}
