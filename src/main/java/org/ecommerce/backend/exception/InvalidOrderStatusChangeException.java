package org.ecommerce.backend.exception;

import io.smallrye.graphql.api.ErrorCode;
import org.ecommerce.common.enums.OrderStatusEn;

@ErrorCode("INVALID_ORDER_STATUS_CHANGE")
public class InvalidOrderStatusChangeException extends RuntimeException
{
    private final OrderStatusEn from;
    private final OrderStatusEn to;

    public InvalidOrderStatusChangeException(OrderStatusEn from, OrderStatusEn to)
    {
        this("Cannot move an order from " + from + " to " + to, from, to);
    }

    private InvalidOrderStatusChangeException(String message, OrderStatusEn from, OrderStatusEn to)
    {
        super(message);
        this.from = from;
        this.to = to;
    }

    public static InvalidOrderStatusChangeException unknownStatus(String supplied)
    {
        String message = supplied == null || supplied.isBlank()
                ? "No target status supplied"
                : "'" + supplied + "' is not a valid order status";
        return new InvalidOrderStatusChangeException(message, null, null);
    }

    public static InvalidOrderStatusChangeException trackingNotAllowed(OrderStatusEn target)
    {
        return new InvalidOrderStatusChangeException(
                "Tracking details belong to the move to IN_TRANSIT, not to " + target, null, target);
    }

    public OrderStatusEn getFrom()
    {
        return from;
    }

    public OrderStatusEn getTo()
    {
        return to;
    }
}
