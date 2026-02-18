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

    @Mutation("createOrder")
    @Description("Create a order and return")
    public OrderEntity createOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        if (orderDto == null){
            throw new GraphQLException("Invalid Order info");
        }
        System.out.println("DEBUG Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
        return orderService.createOrderFromDto(orderDto);
    }

    @Mutation("updateOrder")
    @Description("Update an order and return")
    public OrderEntity updateOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        OrderEntity order = OrderEntity.findById(orderDto.getOrderId());
        if (order == null){
            throw new GraphQLException("Invalid Order info");
        }
        return order;
    }
}
