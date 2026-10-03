package org.ecommerce.backend.exception;

import io.smallrye.graphql.api.ErrorCode;

import java.util.UUID;

@ErrorCode("ORDER_NOT_FOUND")
public class OrderNotFoundException extends RuntimeException
{
    private final UUID orderId;

    public OrderNotFoundException(UUID orderId)
    {
        super(orderId == null ? "Order not found" : "Order not found: " + orderId);
        this.orderId = orderId;
    }

    public UUID getOrderId()
    {
        return orderId;
    }
}
