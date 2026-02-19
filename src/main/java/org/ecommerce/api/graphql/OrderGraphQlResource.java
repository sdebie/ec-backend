package org.ecommerce.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.persistance.dto.CustomerDto;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.OrderEntity;
import org.ecommerce.service.OrderService;

import java.util.List;

@ApplicationScoped
@GraphQLApi
public class OrderGraphQlResource {
    @Inject
    OrderService orderService;

    @Mutation("createOrder")
    @Description("Create an order")
    public OrderEntity createOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        System.out.println("DEBUG:: Received createOrder request");
        if (orderDto == null) {
            throw new GraphQLException("Invalid Order info");
        }

        if (orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }

        System.out.println("DEBUG:: Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
        return orderService.createOrderFromDto(orderDto);
    }

//    @Mutation("addToCart")
//    @Description("Add item to cart/ will update or create an order")
//    public OrderEntity addToCart(@Name("order") OrderDto orderDto) throws GraphQLException {
//        System.out.println("DEBUG:: Received addToCart request");
//
//        if (orderDto == null) {
//            throw new GraphQLException("Invalid Order info");
//        }
//        // 1) If client provided a specific orderId, update that order
//        if (orderDto.getOrderId() != null) {
//            OrderEntity existingOrder = orderService.getOrderById(orderDto.getOrderId());
//            if (existingOrder == null) throw new GraphQLException("Invalid Order info");
//            OrderEntity updated = orderService.updateOrder(orderDto);
//            return updated;
//        }
//        // 2) Otherwise, if a sessionId is provided, try to update the latest order for that session
//        if (orderDto.getSessionId() != null) {
//            OrderEntity latest = orderService.getLatestOrderBySessionId(orderDto.getSessionId());
//            if (latest != null) {
//                orderDto.setOrderId(latest.id);
//                OrderEntity updated = orderService.updateOrder(orderDto);
//                return updated;
//            }
//        }
//        // 3) Fallback: create a new order (sessionId will be set/generated in service)
//        System.out.println("DEBUG:: Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
//        OrderEntity created = orderService.createOrderFromDto(orderDto);
//        return created;
//    }

//    @Mutation("updateOrder")
//    @Description("Update an order and return")
//    public OrderEntity updateOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
//        System.out.println("DEBUG:: Received updateOrder request");
//        OrderEntity updated = orderService.updateOrder(orderDto);
//        return updated;
//    }

    @Mutation("updateCustomerInformation")
    @Description("Update customer information for the latest order in a session. For now only email is supported.")
    public CustomerDto updateCustomerInformation(
            @Name("sessionId") String sessionId,
            @Name("customer") CustomerDto customerDto
    ) throws GraphQLException {
        System.out.println("DEBUG:: Received updateCustomerInformation request for sessionId=" + sessionId);
        return orderService.updateCustomerInformation(sessionId, customerDto);
    }

    @Query("orderById")
    @Description("Update an order and return")
    public OrderEntity getOrderById(@Name("id") Long id) {
        System.out.println("DEBUG:: Received getOrderById request");
        OrderEntity order = orderService.getOrderById(id);
        return order;
    }

    @Query("orderBySessionId")
    @Description("Get the latest order for a given sessionId")
    public OrderEntity getOrderBySessionId(@Name("sessionId") String sessionId) {
        System.out.println("DEBUG:: Received getOrderBySessionId request");
        OrderEntity order = orderService.getLatestOrderBySessionId(sessionId);
        return order;
    }
    
    
}
