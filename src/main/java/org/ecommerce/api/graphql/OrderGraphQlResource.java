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
    public OrderEntity createOrder(@Name("order") OrderDto orderDto) {
        OrderEntity order = new OrderEntity();
        // Use field access to avoid reliance on Lombok-generated setters
        order.setTotalAmount(orderDto.getTotal_amount());
        order.status = "CREATED";
        order.items = orderDto.getItems();
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
