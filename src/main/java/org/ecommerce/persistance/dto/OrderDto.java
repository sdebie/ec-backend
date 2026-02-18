package org.ecommerce.persistance.dto;

import lombok.Data;
import org.ecommerce.persistance.entity.OrderItemEntity;

import java.util.List;

@Data
public class OrderDto {
    // Use wrapper type Long so GraphQL input can be nullable during create
    Long orderId;
    List<OrderItemEntity> items;

    // Explicit getters to avoid reliance on Lombok during build
    public Long getOrderId() {
        return orderId;
    }

    public List<OrderItemEntity> getItems() {
        return items;
    }
}
