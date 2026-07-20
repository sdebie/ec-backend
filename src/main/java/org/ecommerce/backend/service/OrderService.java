package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.common.dto.OrderCheckoutLineDto;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderDto;
import org.ecommerce.common.dto.OrderItemDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.backend.mapper.OrderMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

@ApplicationScoped
public class OrderService
{
    @Inject
    OrderNotificationService orderNotificationService;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderMapper orderMapper;

    @Inject
    PricingService pricingService;

    @Inject
    TaxService taxService;

    @Inject
    ShippingService shippingService;

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    @Transactional
    public OrderCheckoutResponseDto createOrderFromCart(OrderCreationRequestDto request, CustomerTypeEn customerTier)
    {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one item");
        }

        List<String> unavailableVariantIds = new ArrayList<>();
        List<OrderCreationItemDto> validItems = new ArrayList<>();
        List<ProductVariantEntity> validVariants = new ArrayList<>();

        // 1. Validate each item: existence, active status, and stock
        for (OrderCreationItemDto item : request.getItems()) {
            if (item == null || item.getVariantId() == null) {
                continue;
            }
            UUID variantUuid;
            try {
                variantUuid = UUID.fromString(item.getVariantId());
            } catch (IllegalArgumentException e) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(variantUuid);
            if (variant == null || variant.status != ProductStatusEn.ACTIVE) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            int requested = item.getQuantity() != null ? item.getQuantity() : 0;
            if (requested <= 0 || variant.stockQuantity == null || variant.stockQuantity < requested) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            validItems.add(item);
            validVariants.add(variant);
        }

        // 2. Bail out if any variants are unavailable
        if (!unavailableVariantIds.isEmpty()) {
            throw new UnavailableVariantsException(unavailableVariantIds);
        }

        // 3. Build checkout lines from server-side pricing
        List<OrderCheckoutLineDto> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);

            BigDecimal unitPrice = pricingService.getActivePrice(variant.id, customerTier);
            int quantity = item.getQuantity();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

            OrderCheckoutLineDto line = new OrderCheckoutLineDto();
            line.setVariantId(variant.id.toString());
            line.setName(variant.product != null ? variant.product.name : null);
            line.setUnitPrice(unitPrice);
            line.setQuantity(quantity);
            line.setLineTotal(lineTotal);
            lines.add(line);

            subtotal = subtotal.add(lineTotal);
        }

        // 4 & 5. Calculate tax and shipping
        BigDecimal vatAmount = taxService.calculateVat(subtotal);
        BigDecimal shippingEstimate = shippingService.estimateShipping();
        BigDecimal grandTotal = subtotal.add(vatAmount).add(shippingEstimate);

        // 6. Persist the order
        UUID sessionId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.sessionId = sessionId;
        order.totalAmount = grandTotal;
        order.status = OrderStatusEn.CREATED;

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);
            BigDecimal unitPrice = lines.get(i).getUnitPrice();

            OrderItemEntity orderItem = new OrderItemEntity();
            orderItem.orderEntity = order;
            orderItem.variant = variant;
            orderItem.quantity = item.getQuantity();
            orderItem.unitPrice = unitPrice;
            order.items.add(orderItem);
        }

        OrderEntity.persist(order);

        // 7. Build and return response
        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(order.id.toString());
        response.setSessionId(sessionId.toString());
        response.setLines(lines);
        response.setSubtotal(subtotal);
        response.setVatAmount(vatAmount);
        response.setShippingEstimate(shippingEstimate);
        response.setGrandTotal(grandTotal);
        return response;
    }

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
            LOG.debugf("Creating new order for sessionId=%s", session);
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
            LOG.debugf("getLatestOrderBySessionId for sessionId=%s", sid);
            return orderRepository.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
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
        LOG.debugf("Updating order status for sessionId=%s to status=%s", sessionId, newStatus);
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
            orderNotificationService.sendConfirmationEmail(order);
        }
        return orderMapper.toResponseDto(order);
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

        return orderMapper.toDetailDto(order);
    }

    public List<OrderSummaryDto> getMyOrders(UUID customerId) {
        List<OrderEntity> orders = OrderEntity
                .find("customerEntity.id = ?1 order by createdAt desc", customerId)
                .list();

        return orders.stream()
                .map(orderMapper::toSummaryDto)
                .collect(Collectors.toList());
    }

}