package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderDto;
import org.ecommerce.common.dto.OrderItemDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.backend.mapper.OrderMapper;

import java.math.BigDecimal;
import java.util.*;

import org.jboss.logging.Logger;

@ApplicationScoped
public class OrderService
{
    @Inject
    MailTemplate order_confirmation;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderMapper orderMapper;

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    @Transactional
    public OrderResponseDto createOrderFromDto(OrderDto orderDto) throws GraphQLException
    {

        if (orderDto == null || orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }
        UUID session = UUID.fromString(orderDto.getSessionId());
        OrderEntity order = orderRepository.findLatestOrderInfoBySessionId(session);
        boolean isNew = false;
        if (order == null) {
            System.out.println("DEBUG: Creating new Order for sessionId=" + session);
            order = new OrderEntity();
            order.sessionId = session;
            isNew = true;
        } else {
            // Prepare existing items for reconciliation (no bulk clear)
            if (order.items == null) {
                order.items = new java.util.ArrayList<>();
            }
        }

        // Map minimal fields from DTO
        BigDecimal dtoTotal = orderDto.getTotalAmount();
        if (isNew) {
            order.status = OrderStatusEn.CREATED;
        }

        // Reconcile items (cascade + orphanRemoval on OrderEntity will handle DB writes)
        List<OrderItemDto> dtoItems = orderDto.getItems();
        BigDecimal computedTotal = BigDecimal.ZERO;
        if (order.items == null) {
            order.items = new java.util.ArrayList<>();
        }

        // Build lookup of existing items by variant id (only for items that have a variant)
        Map<UUID, OrderItemEntity> existingByVariant = new HashMap<>();
        for (OrderItemEntity it : order.items) {
            if (it != null && it.variant != null && it.variant.id != null) {
                existingByVariant.put(it.variant.id, it);
            }
        }

        Set<UUID> seenVariantIds = new HashSet<>();

        if (dtoItems != null) {
            for (OrderItemDto dtoItem : dtoItems) {
                if (dtoItem == null) continue;

                ProductVariantEntity variant = null;
                String variantId = dtoItem.getVariant();
                if (variantId != null) {
                    variant = ProductVariantEntity.findByIdWithProduct(UUID.fromString(variantId));
                }

                OrderItemEntity target = null;
                if (variant != null && variant.id != null) {
                    seenVariantIds.add(variant.id);
                    target = existingByVariant.get(variant.id);
                }

                if (target == null) {
                    // Create a new item (either no matching variant or variant is null)
                    target = new OrderItemEntity();
                    target.id = null;
                    target.orderEntity = order;
                    target.variant = variant; // may be null
                    order.items.add(target);
                }

                // Update mutable fields
                target.unitPrice = dtoItem.getUnitPrice();
                target.quantity = dtoItem.getQuantity();

                BigDecimal unit = target.unitPrice != null ? target.unitPrice : BigDecimal.ZERO;
                int qty = target.quantity != null ? target.quantity : 0;
                computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
            }
        }

        // Remove orphan items that have a variant not present in the DTO
        if (!existingByVariant.isEmpty()) {
            Iterator<OrderItemEntity> iter = order.items.iterator();
            while (iter.hasNext()) {
                OrderItemEntity it = iter.next();
                if (it != null && it.variant != null && it.variant.id != null) {
                    if (!seenVariantIds.contains(it.variant.id)) {
                        iter.remove(); // triggers orphanRemoval
                    }
                }
            }
        }

        // If total not provided, use computed
        order.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;

        if (order.id == null) {
            OrderEntity.persist(order);
        } // else: entity already managed; no explicit persist needed

        return orderMapper.toResponseDto(order);
    }

    public OrderResponseDto getOrderById(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }
        return orderMapper.toResponseDto(orderRepository.findOrderInfoById(orderId));
    }

    public OrderResponseDto getLatestOrderBySessionId(String sessionId)
    {
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        return orderMapper.toResponseDto(order);
    }

    private OrderEntity findLatestOrderEntityBySessionId(String sessionId)
    {
        try {
            UUID sid = UUID.fromString(sessionId);
            System.out.println("DEBUG: getLatestOrderBySessionId for sessionId=" + sid);
            return orderRepository.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public CustomerDto updateCustomerInformation(String sessionId, CustomerDto customerDto) throws GraphQLException
    {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("sessionId is required");
        }
        if (customerDto == null || customerDto.getEmail() == null || customerDto.getEmail().isBlank()) {
            throw new GraphQLException("customer email is required");
        }

        System.out.println("DEBUG: Updating customer info for sessionId=" + sessionId + " email=" + customerDto.getEmail());
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        if (order == null) {
            throw new GraphQLException("Order not found for sessionId");
        }

        System.out.println("DEBUG: Found Order with Items=" + order.items);

        String email = customerDto.getEmail().trim();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            customer = new CustomerEntity();
            customer.email = email;
            CustomerEntity.persist(customer);
        }

        order.customerEntity = customer;
        System.out.println("DEBUG: Updating Order with customer info=" + order.customerEntity.id);
        // no explicit persist needed; managed entity will be updated on commit

        // Return only customer information (currently email)
        CustomerDto result = new CustomerDto();
        result.setEmail(customer.email);
        return result;
    }

    @Transactional
    public OrderResponseDto updateOrderStatus(String sessionId, String newStatus) throws GraphQLException
    {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("sessionId is required");
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw new GraphQLException("status is required");
        }
        System.out.println("DEBUG: Updating order status for sessionId=" + sessionId + " to status=" + newStatus);
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        if (order == null) {
            throw new GraphQLException("Order not found for sessionId");
        }
        try {
            order.status = OrderStatusEn.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw new GraphQLException("Invalid status: " + newStatus);
        }
        order.persist(); // ensure status update is saved before creating history record


        // 2. Create history record
        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity();
        history.order = order;
        history.status = OrderStatusEn.valueOf(newStatus);
        history.comment = "Order Update";
        //history.changedBy = staffId;

        history.persist();


        //Order Created In store Payment
        if (order.status.equals(OrderStatusEn.IN_STORE_PAYMENT)) {
            sendConfirmationEmail(order);
        }
        return orderMapper.toResponseDto(order);
    }

    public void sendConfirmationEmail(OrderEntity order)
    {
        String firstName = (order.customerEntity.firstName != null && !order.customerEntity.firstName.isBlank()) ? order.customerEntity.firstName : "Guest";
        order_confirmation.to(order.customerEntity.email)
                .from("shawn.debie@gmail.com")
                .subject("Your Order #" + order.id)
                .data("order", order)
                .data("orderItems", order.items)
                .data("customerName", firstName)
                .send()
                .subscribe().with(
                        success -> LOG.info("Order email sent!"),
                        failure -> LOG.error("Order email failed", failure)
                );
    }

    public List<OrderResponseDto> getAllOrders(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<OrderEntity> orderEntities = orderRepository.findAllOrderInfo(pageRequest, filterRequest);
        List<OrderResponseDto> orders = new ArrayList<>(orderEntities.size());
        for (OrderEntity orderEntity : orderEntities) {
            orders.add(orderMapper.toResponseDto(orderEntity));
        }
        return orders;
    }

    public OrderDetailRespDto getOrderDetail(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }

        OrderEntity order = orderRepository.findOrderInfoById(orderId);
        if (order == null) {
            return null;
        }

        OrderDetailRespDto detail = new OrderDetailRespDto();

        // Map all OrderEntity fields
        detail.id = order.id;
        detail.customerEntity = order.customerEntity;
        detail.totalAmount = order.totalAmount;
        detail.sessionId = order.sessionId;
        detail.status = order.status;
        detail.shippingPhone = order.shippingPhone;
        detail.shippingAddressLine1 = order.shippingAddressLine1;
        detail.shippingAddressLine2 = order.shippingAddressLine2;
        detail.shippingCity = order.shippingCity;
        detail.shippingProvince = order.shippingProvince;
        detail.shippingPostalCode = order.shippingPostalCode;
        if (order.items != null) {
            detail.items = new ArrayList<>(order.items);
        }
        detail.createdAt = order.createdAt;

        // Map all OrderStatusHistoryEntity fields
        List<OrderStatusHistoryEntity> histories = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", orderId)
                .list();

        if (histories != null) {
            for (OrderStatusHistoryEntity history : histories) {
                if (history == null) {
                    continue;
                }
                OrderDetailRespDto.OrderStatusHistoryDetailRespDto historyDto =
                        new OrderDetailRespDto.OrderStatusHistoryDetailRespDto();
                historyDto.id = history.id;
                historyDto.order = history.order;
                historyDto.status = history.status;
                historyDto.comment = history.comment;
                historyDto.changedBy = history.changedBy;
                historyDto.createdAt = history.createdAt;
                detail.statusHistory.add(historyDto);
            }
        }

        return detail;
    }

}
