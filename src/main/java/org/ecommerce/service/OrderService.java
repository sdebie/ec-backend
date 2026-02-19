package org.ecommerce.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.persistance.dto.CustomerDto;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.dto.OrderItemDto;
import org.ecommerce.persistance.entity.CustomerEntity;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.persistance.entity.OrderItemEntity;
import org.ecommerce.persistance.entity.ProductVariantEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderService {

    @Transactional
    public OrderEntity createOrderFromDto(OrderDto orderDto) throws GraphQLException {

        if (orderDto == null || orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }
        UUID session = UUID.fromString(orderDto.getSessionId());
        OrderEntity order = OrderEntity.findLatestOrderInfoBySessionId(session);
        if (order == null) {
            System.out.println("DEBUG: Creating new Order for sessionId=" + session);
            order = new OrderEntity();
            order.sessionId = session;
        }

        // Map minimal fields from DTO
        BigDecimal dtoTotal = orderDto.getTotalAmount();
        order.status = "CREATED";

        // Map and attach items (will be persisted via cascade from OrderEntity)
        List<OrderItemDto> dtoItems = orderDto.getItems();
        if (dtoItems != null && !dtoItems.isEmpty()) {
            BigDecimal computedTotal = BigDecimal.ZERO;
            java.util.ArrayList<OrderItemEntity> entities = new java.util.ArrayList<>();
            for (OrderItemDto dtoItem : dtoItems) {
                if (dtoItem == null)
                    continue;
                OrderItemEntity item = new OrderItemEntity();
                item.id = null; // PanacheEntity id
                item.orderEntity = order;
                item.unitPrice = dtoItem.getUnitPrice();
                item.quantity = dtoItem.getQuantity();

                // Map variant by id if provided
                if (dtoItem.getVariant() != null) {
                    ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(dtoItem.getVariant());
                    if (variant != null) {
                        item.variant = variant;
                    }
                }

                // Update running total defensively
                BigDecimal unit = item.unitPrice != null ? item.unitPrice : BigDecimal.ZERO;
                int qty = item.quantity != null ? item.quantity : 0;
                computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
                entities.add(item);
            }
            order.items = entities;
            // If total not provided, use computed
            order.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;
        } else {
            order.totalAmount = BigDecimal.ZERO;
        }

        OrderEntity.persist(order);
        return order;
    }

    public OrderEntity getOrderById(Long orderId) {
        return OrderEntity.findOrderInfoById(orderId);
    }

    public OrderEntity getLatestOrderBySessionId(String sessionId) {
        try {
            UUID sid = UUID.fromString(sessionId);
            System.out.println("DEBUG: getLatestOrderBySessionId for sessionId=" + sid);
            return OrderEntity.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
    }
//
//    @Transactional
//    public OrderEntity updateOrder(OrderDto orderDto) throws GraphQLException {
//        if (orderDto == null || orderDto.getOrderId() == null) {
//            throw new GraphQLException("Invalid Order info");
//        }
//        OrderEntity existingOrder = OrderEntity.findOrderInfoById(orderDto.getOrderId());
//        if (existingOrder == null){
//            throw new GraphQLException("Invalid Order info");
//        }
//
//        // Overwrite items
//        List<OrderItemDto> incomingItems = orderDto.getItems();
//
//        // Prepare managed collection for update (do not replace the collection reference)
//        if (existingOrder.items == null) {
//            existingOrder.items = new java.util.ArrayList<>();
//        } else {
//            existingOrder.items.clear();
//        }
//
//        BigDecimal dtoTotal = orderDto.getTotalAmount();
//        BigDecimal computedTotal = BigDecimal.ZERO;
//
//        if (incomingItems != null && !incomingItems.isEmpty()) {
//            for (OrderItemDto dtoItem : incomingItems) {
//                if (dtoItem == null)
//                    continue;
//                OrderItemEntity item = new OrderItemEntity();
//                // Ensure these are treated as new rows
//                item.id = null;
//                // Set back-reference for FK integrity
//                item.orderEntity = existingOrder;
//                item.unitPrice = dtoItem.getUnitPrice();
//                item.quantity = dtoItem.getQuantity();
//
//                // Map variant by id if provided
//                if (dtoItem.getVariant() != null) {
//                    ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(dtoItem.getVariant());
//                    if (variant != null) {
//                        item.variant = variant;
//                    }
//                }
//
//                BigDecimal unit = item.unitPrice != null ? item.unitPrice : BigDecimal.ZERO;
//                int qty = item.quantity != null ? item.quantity : 0;
//                computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
//
//                // Add new item to managed collection
//                existingOrder.items.add(item);
//            }
//        } else {
//            // No items provided -> overwrite to empty
//            // Keep items as cleared (empty) collection if it exists; otherwise leave null
//        }
//
//        // Update total: prefer provided total, otherwise computed
//        existingOrder.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;
//
//        return existingOrder;
//    }

    @Transactional
    public CustomerDto updateCustomerInformation(String sessionId, CustomerDto customerDto) throws GraphQLException {
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

        // Return only customer information (currently email)
        CustomerDto result = new CustomerDto();
        result.setEmail(customer.email);
        return result;
    }
}
