package org.ecommerce.backend.exception;

import io.smallrye.graphql.api.ErrorCode;
import org.ecommerce.common.enums.OrderStatusEn;

import java.util.UUID;

@ErrorCode("ORDER_STATUS_CONFLICT")
public class OrderStatusConflictException extends RuntimeException
{
    private final UUID orderId;
    private final OrderStatusEn expectedFrom;

    public OrderStatusConflictException(UUID orderId, OrderStatusEn expectedFrom)
    {
        super("Order status changed concurrently; please refresh and try again");
        this.orderId = orderId;
        this.expectedFrom = expectedFrom;
    }

    public UUID getOrderId()
    {
        return orderId;
    }

    public OrderStatusEn getExpectedFrom()
    {
        return expectedFrom;
    }
}
