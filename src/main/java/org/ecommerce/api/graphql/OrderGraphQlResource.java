package org.ecommerce.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.service.OrderService;

@ApplicationScoped
@GraphQLApi
public class OrderGraphQlResource {
    @Inject
    OrderService orderService;

    @Mutation("addToCart")
    @Description("Add item to cart/ will update or create an order")
    public OrderEntity addToCart(@Name("order") OrderDto orderDto) throws GraphQLException {
        if (orderDto == null) {
            throw new GraphQLException("Invalid Order info");
        }
        // 1) If client provided a specific orderId, update that order
        if (orderDto.getOrderId() != null) {
            OrderEntity existingOrder = orderService.getOrderById(orderDto.getOrderId());
            if (existingOrder == null) throw new GraphQLException("Invalid Order info");
            return orderService.updateOrder(orderDto);
        }
        // 2) Otherwise, if a sessionId is provided, try to update the latest order for that session
        if (orderDto.getSessionId() != null) {
            OrderEntity latest = orderService.getLatestOrderBySessionId(orderDto.getSessionId());
            if (latest != null) {
                orderDto.setOrderId(latest.id);
                return orderService.updateOrder(orderDto);
            }
        }
        // 3) Fallback: create a new order (sessionId will be set/generated in service)
        System.out.println("DEBUG Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
        return orderService.createOrderFromDto(orderDto);
    }

    @Mutation("updateOrder")
    @Description("Update an order and return")
    public OrderEntity updateOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        return orderService.updateOrder(orderDto);
    }

    @Query("orderById")
    @Description("Update an order and return")
    public OrderEntity getOrderById(@Name("id") Long id) {
        return orderService.getOrderById(id);
    }

    @Query("orderBySessionId")
    @Description("Get the latest order for a given sessionId")
    public OrderEntity getOrderBySessionId(@Name("sessionId") String sessionId) {
        return orderService.getLatestOrderBySessionId(sessionId);
    }
}
