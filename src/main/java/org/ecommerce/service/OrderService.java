package org.ecommerce.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.persistance.entity.OrderItemEntity;

import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class OrderService {

    @Transactional
    public OrderEntity createOrderFromDto(OrderDto orderDto) {
        OrderEntity order = new OrderEntity();
        // Map minimal fields from DTO
        BigDecimal dtoTotal = orderDto != null ? orderDto.getTotalAmount() : null;
        order.status = "CREATED";
        // Ensure session id is set (from DTO or generate new)
        try {
            String sid = orderDto != null ? orderDto.getSessionId() : null;
            if (sid != null && !sid.isBlank()) {
                order.sessionId = java.util.UUID.fromString(sid);
            } else {
                order.sessionId = java.util.UUID.randomUUID();
            }
        } catch (Exception e) {
            // If provided value is invalid, generate a new UUID
            order.sessionId = java.util.UUID.randomUUID();
        }

        // Map and attach items (will be persisted via cascade from OrderEntity)
        if (orderDto != null) {
            List<OrderItemEntity> items = orderDto.getItems();
            if (items != null && !items.isEmpty()) {
                BigDecimal computedTotal = BigDecimal.ZERO;
                for (OrderItemEntity item : items) {
                    if (item == null)
                        continue;
                    // Ensure insert of new items
                    item.id = null; // PanacheEntity id
                    // Set back-reference for JPA
                    item.orderEntity = order;
                    // Update running total defensively
                    BigDecimal unit = item.unitPrice != null ? item.unitPrice : BigDecimal.ZERO;
                    int qty = item.quantity != null ? item.quantity : 0;
                    computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
                }
                order.items = items;
                // If total not provided, use computed
                order.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;
            } else {
                order.totalAmount = dtoTotal != null ? dtoTotal : BigDecimal.ZERO;
            }
        } else {
            order.totalAmount = BigDecimal.ZERO;
        }

        OrderEntity.persist(order);
        return order;
    }

    public OrderEntity getOrderById(Long orderId) {
        return OrderEntity.findById(orderId);
    }

    public OrderEntity getLatestOrderBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            java.util.UUID sid = java.util.UUID.fromString(sessionId);
            return OrderEntity.find("sessionId = ?1 order by id desc", sid).firstResult();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public OrderEntity updateOrder(OrderDto orderDto) throws GraphQLException {
        if (orderDto == null || orderDto.getOrderId() == null) {
            throw new GraphQLException("Invalid Order info");
        }
        OrderEntity existingOrder = OrderEntity.findById(orderDto.getOrderId());
        if (existingOrder == null){
            throw new GraphQLException("Invalid Order info");
        }

        // Overwrite items
        List<OrderItemEntity> incomingItems = orderDto.getItems();

        // Clear existing items to trigger orphan removal
        if (existingOrder.items != null) {
            existingOrder.items.clear();
        }

        BigDecimal dtoTotal = orderDto.getTotalAmount();
        BigDecimal computedTotal = BigDecimal.ZERO;

        if (incomingItems != null && !incomingItems.isEmpty()) {
            for (OrderItemEntity item : incomingItems) {
                if (item == null) continue;
                // Ensure these are treated as new rows
                item.id = null;
                // Set back-reference for FK integrity
                item.orderEntity = existingOrder;

                BigDecimal unit = item.unitPrice != null ? item.unitPrice : BigDecimal.ZERO;
                int qty = item.quantity != null ? item.quantity : 0;
                computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
            }
            // Attach new items list
            existingOrder.items = incomingItems;
        } else {
            // No items provided -> overwrite to empty
            // Keep items as cleared (empty) collection if it exists; otherwise leave null
        }

        // Update total: prefer provided total, otherwise computed
        existingOrder.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;

        return existingOrder;
    }
}
