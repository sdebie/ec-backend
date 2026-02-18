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
        OrderEntity updatedEntity;
        if (orderDto == null){
            throw new GraphQLException("Invalid Order info");
        }
        if (orderDto.getOrderId() != null){
            OrderEntity existingOrder = orderService.getOrderById(orderDto.getOrderId());
            if (existingOrder != null){
                updatedEntity = orderService.updateOrder(orderDto);
            }
            else{
                throw new GraphQLException("Invalid Order info");
            }
        }
        else {
            System.out.println("DEBUG Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
            updatedEntity = orderService.createOrderFromDto(orderDto);
        }
        return updatedEntity;
    }

    @Mutation("updateOrder")
    @Description("Update an order and return")
    public OrderEntity updateOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        return orderService.updateOrder(orderDto);
    }
}
