package org.ecommerce.persistance.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Input;

import java.math.BigDecimal;
import java.util.List;

@Input("OrderDtoInput")
@Description("Input type for creating/updating an order")
public class OrderDto {
    // Use wrapper type Long so GraphQL input can be nullable during create
    private Long orderId;

    // Frontend cart session identifier (UUID string)
    private String sessionId;

    // Prefer camelCase for GraphQL schema
    private BigDecimal totalAmount;

    private List<OrderItemDto> items;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public List<OrderItemDto> getItems() { return items; }
    public void setItems(List<OrderItemDto> items) { this.items = items; }
}
