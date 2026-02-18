package org.ecommerce.persistance.dto;

import lombok.Data;
import org.ecommerce.persistance.entity.OrderItemEntity;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderDto {
    // Explicit getters to avoid reliance on Lombok during build
    // Use wrapper type Long so GraphQL input can be nullable during create
    Long orderId;
    BigDecimal total_amount;
    List<OrderItemEntity> items;

}
