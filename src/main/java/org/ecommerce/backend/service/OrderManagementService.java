package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.exception.InvalidOrderStatusChangeException;
import org.ecommerce.backend.exception.OrderNotFoundException;
import org.ecommerce.backend.exception.OrderStatusConflictException;
import org.ecommerce.backend.mapper.OrderMapper;
import org.ecommerce.common.dto.OrderDetailDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.StockEffect;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.common.repository.OrderStatusHistoryRepository;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class OrderManagementService
{
    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    @Inject
    OrderNotificationService orderNotificationService;

    @Inject
    OrderMapper orderMapper;

    private static final Logger LOG = Logger.getLogger(OrderManagementService.class);

    public boolean changeOrderStatus(OrderEntity order, StatusTransition transition) {
        OrderStatusEn from = order.getStatus();
        OrderStatusEn to = transition.to();

        if (transition.expectedFrom() != null && from != transition.expectedFrom()) {
            LOG.debugf("Order %s is %s, not the expected %s; another writer moved it first", order.getId(), from, transition.expectedFrom());
            return false;
        }

        boolean permitted = transition.source() == TransitionSource.STAFF ? from != null && from.canTransitionTo(to) : from != null && from.canSystemTransitionTo(to);
        if (!permitted) {
            throw new InvalidOrderStatusChangeException(from, to);
        }

        long claimed = orderRepository.update("status = ?1 where id = ?2 and status = ?3", to, order.getId(), from);
        if (claimed == 0) {
            LOG.debugf("Lost the status claim on order %s: it is no longer %s", order.getId(), from);
            return false;
        }
        order.setStatus(to);

        boolean stockReturned = to.stockEffect() == StockEffect.RESTORE;
        if (stockReturned) {
            restoreStock(order);
        }

        orderStatusHistoryRepository.record(order, to, statusChangeComment(from, transition), transition.changedBy());

        orderNotificationService.sendStatusNotification(order, to);

        return true;
    }

    private String statusChangeComment(OrderStatusEn from, StatusTransition transition) {
        return transition.comment() != null ? transition.comment() : from + " → " + transition.to();
    }

    private void restoreStock(OrderEntity order) {
        if (order == null || order.getItems() == null) {
            return;
        }

        for (OrderItemEntity item : order.getItems()) {
            if (item.getVariant() == null || item.getQuantity() == null) {
                continue;
            }
            productVariantRepository.update("stockQuantity = stockQuantity + ?1 where id = ?2", item.getQuantity(), item.getVariant().getId());
        }
    }

    @Transactional
    public OrderDetailDto updateOrderStatus(UUID orderId, String newStatus, String changedBy) {
        return updateOrderStatus(orderId, newStatus, changedBy, null);
    }

    @Transactional
    public OrderDetailDto updateOrderStatus(UUID orderId, String newStatus, String changedBy, OrderTracking tracking) {
        if (orderId == null) {
            throw new OrderNotFoundException(null);
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw InvalidOrderStatusChangeException.unknownStatus(newStatus);
        }
        LOG.debugf("Updating order status for orderId=%s to status=%s", orderId, newStatus);
        OrderEntity order = orderRepository.findByIdWithCustomerAndItems(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        OrderStatusEn targetStatus;
        try {
            targetStatus = OrderStatusEn.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw InvalidOrderStatusChangeException.unknownStatus(newStatus);
        }

        if (tracking != null && !tracking.isEmpty()) {
            if (targetStatus != OrderStatusEn.IN_TRANSIT) {
                throw InvalidOrderStatusChangeException.trackingNotAllowed(targetStatus);
            }
            order.setTrackingNumber(tracking.number());
            order.setTrackingCarrier(tracking.carrier());
        }

        boolean changed = changeOrderStatus(order, StatusTransition.staff(targetStatus, changedBy));
        if (!changed) {
            throw new OrderStatusConflictException(orderId, order.getStatus());
        }

        Map<UUID, List<ProductImageEntity>> imagesByVariantId = productImageRepository.findGroupedByVariantIds(variantIdsOf(List.of(order)));
        return orderMapper.toOrderDto(order, imagesByVariantId);
    }

    private List<UUID> variantIdsOf(List<OrderEntity> orders) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (OrderEntity order : orders) {
            if (order == null || order.getItems() == null) {
                continue;
            }
            for (OrderItemEntity item : order.getItems()) {
                if (item != null && item.getVariant() != null && item.getVariant().getId() != null) {
                    ids.add(item.getVariant().getId());
                }
            }
        }
        return new ArrayList<>(ids);
    }
}
