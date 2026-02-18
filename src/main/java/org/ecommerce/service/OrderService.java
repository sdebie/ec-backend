package org.ecommerce.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.OrderEntity;

import java.math.BigDecimal;

@ApplicationScoped
public class OrderService {

    @Transactional
    public OrderEntity createOrderFromDto(OrderDto orderDto) {
        OrderEntity order = new OrderEntity();
        // Map minimal fields from DTO
        order.totalAmount = orderDto != null && orderDto.getTotalAmount() != null
                ? orderDto.getTotalAmount()
                : BigDecimal.ZERO;
        order.status = "CREATED";

        // TODO: Map items when frontend provides variantId and when persistence is ready
        // Skipping items mapping to avoid constraint violations until payload is aligned

        OrderEntity.persist(order);
        return order;
    }
}
