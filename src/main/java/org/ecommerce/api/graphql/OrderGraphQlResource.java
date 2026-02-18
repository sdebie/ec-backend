package org.ecommerce.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.persistance.dto.OrderDto;
import org.ecommerce.persistance.entity.OrderEntity;

@ApplicationScoped
@GraphQLApi
public class OrderGraphQlResource {
    @Query("createOrder")
    @Description("Create a order and return")
    @Transactional
    public OrderEntity createOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        if (orderDto == null){
            throw new GraphQLException("Invalid Order info");
        }
        System.out.println("DEBUG Received OrderDto: " + orderDto.getTotalAmount() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
        OrderEntity order = new OrderEntity();
        order.setTotalAmount(orderDto.getTotalAmount());
        order.items = orderDto.getItems();
        order.status = "CREATED";
        OrderEntity.persist(order);
        return order;
    }

    @Query("updateOrder")
    @Description("Update an order and return")
    @Transactional
    public OrderEntity updateOrder(@Name("order") OrderDto orderDto) throws GraphQLException {
        OrderEntity order = OrderEntity.findById(orderDto.getOrderId());
        if (order == null){
            throw new GraphQLException("Invalid Order info");
        }
        return order;
    }
}
