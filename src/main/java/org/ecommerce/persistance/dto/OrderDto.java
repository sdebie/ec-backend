package org.ecommerce.persistance.dto;

import lombok.Data;
import org.ecommerce.persistance.entity.OrderItemEntity;

import java.util.List;

@Data
public class OrderDto {
    long orderId;
    List<OrderItemEntity> items;

    // Explicit getters to avoid reliance on Lombok during build
    public long getOrderId() {
        return orderId;
    }

    public List<OrderItemEntity> getItems() {
        return items;
    }
}
