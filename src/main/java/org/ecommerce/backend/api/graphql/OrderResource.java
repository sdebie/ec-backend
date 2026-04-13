package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.backend.service.OrderService;

import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class OrderResource
{
    @Inject
    OrderService orderService;

    @Mutation("createOrder")
    @Description("Create an order")
    public OrderResponseDto createOrder(@Name("order") OrderDto orderDto) throws GraphQLException
    {
        System.out.println("DEBUG:: Received createOrder request for Items:" + orderDto.getItems().size());

        if (orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }

        System.out.println("DEBUG:: Received OrderDto: " + orderDto.getSessionId() + " " + (orderDto.getItems() == null ? 0 : orderDto.getItems().size()));
        OrderResponseDto result = orderService.createOrderFromDto(orderDto);
        System.out.println("DEBUG:: Created Order with Items=" + result.items);
        return result;
    }

    @Mutation("updateCustomerInformation")
    @Description("Update customer information for the latest order in a session. For now only email is supported.")
    public CustomerDto updateCustomerInformation(
            @Name("sessionId") String sessionId,
            @Name("customer") CustomerDto customerDto
    ) throws GraphQLException
    {
        System.out.println("DEBUG:: Received updateCustomerInformation request for sessionId=" + sessionId);
        return orderService.updateCustomerInformation(sessionId, customerDto);
    }

    @Mutation("updateOrderStatus")
    @Description("Update the status of the latest order for a given sessionId")
    public OrderResponseDto updateOrderStatus(
            @Name("sessionId") String sessionId,
            @Name("status") String status
    ) throws GraphQLException
    {
        System.out.println("DEBUG:: Received updateOrderStatus request for sessionId=" + sessionId + ", status=" + status);
        return orderService.updateOrderStatus(sessionId, status);
    }

    @Query("orderById")
    @Description("Update an order and return")
    public OrderResponseDto getOrderById(@Name("id") UUID id)
    {
        System.out.println("DEBUG:: Received getOrderById request");
        return orderService.getOrderById(id);
    }

    @Query("orderBySessionId")
    @Description("Get the latest order for a given sessionId")
    public OrderResponseDto getOrderBySessionId(@Name("sessionId") String sessionId) throws GraphQLException
    {
        System.out.println("DEBUG:: Received getOrderBySessionId request: " + sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("Invalid Order Session info");
        }
        return orderService.getLatestOrderBySessionId(sessionId);
    }


}
