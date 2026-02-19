package org.ecommerce.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.persistance.dto.CustomerDto;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.CustomerEntity;
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

    public OrderEntity getLatestOrderBySessionId(Long orderId) {
        // Deprecated name kept for backward compatibility; returns fully-hydrated order
        return OrderEntity.findOrderInfoById(orderId);
    }

    public OrderEntity getOrderById(Long orderId) {
        return OrderEntity.findOrderInfoById(orderId);
    }

    public OrderEntity getLatestOrderBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            java.util.UUID sid = java.util.UUID.fromString(sessionId);
            return OrderEntity.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public OrderEntity updateOrder(OrderDto orderDto) throws GraphQLException {
        if (orderDto == null || orderDto.getOrderId() == null) {
            throw new GraphQLException("Invalid Order info");
        }
        OrderEntity existingOrder = OrderEntity.findOrderInfoById(orderDto.getOrderId());
        if (existingOrder == null){
            throw new GraphQLException("Invalid Order info");
        }

        // Overwrite items
        List<OrderItemEntity> incomingItems = orderDto.getItems();

        // Prepare managed collection for update (do not replace the collection reference)
        if (existingOrder.items == null) {
            existingOrder.items = new java.util.ArrayList<>();
        } else {
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
            // Add new items into managed collection (do not replace reference)
            existingOrder.items.addAll(incomingItems);
        } else {
            // No items provided -> overwrite to empty
            // Keep items as cleared (empty) collection if it exists; otherwise leave null
        }

        // Update total: prefer provided total, otherwise computed
        existingOrder.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;

        return existingOrder;
    }

    @Transactional
    public OrderEntity updateCustomerInformation(String sessionId, CustomerDto customerDto) throws GraphQLException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("sessionId is required");
        }
        if (customerDto == null || customerDto.getEmail() == null || customerDto.getEmail().isBlank()) {
            throw new GraphQLException("customer email is required");
        }

        System.out.println("DEBUG: Updating customer info for sessionId=" + sessionId + " email=" + customerDto.getEmail());
        OrderEntity order = getLatestOrderBySessionId(sessionId);
        if (order == null) {
            throw new GraphQLException("Order not found for sessionId");
        }

        System.out.println("DEBUG: Found Order with Items=" + order.items);

        String email = customerDto.getEmail().trim();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            customer = new CustomerEntity();
            customer.email = email;
            CustomerEntity.persist(customer);
        }

        order.customerEntity = customer;
        System.out.println("DEBUG: Updating Order with customer info=" + order.customerEntity.id);
        order.persist();
        return order;
    }
}
