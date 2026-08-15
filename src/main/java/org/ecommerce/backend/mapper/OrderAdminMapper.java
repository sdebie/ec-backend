package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.dto.AdminOrderAddressDto;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderLineItemDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.AdminOrderStatusHistoryDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps orders into the admin-facing shapes, the same split
 * {@code CustomerAdminMapper} makes for customers: the storefront's
 * {@code OrderMapper} answers a shopper's own questions about their order,
 * this one answers staff's.
 * <p>
 * Pure — no database access. Callers pass entities already hydrated with
 * whatever associations the target shape reads.
 */
@ApplicationScoped
public class OrderAdminMapper
{
    public AdminOrderListItemDto toListItemDto(OrderEntity order)
    {
        if (order == null) {
            return null;
        }

        AdminOrderListItemDto dto = new AdminOrderListItemDto();
        dto.setId(order.getId() == null ? null : order.getId().toString());
        dto.setReference(order.getReference());
        dto.setCustomerName(order.getPlacedByName());
        dto.setPlacedAt(order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
        dto.setItemCount(itemCount(order));
        dto.setTotal(order.getTotalAmount());
        dto.setStatus(order.getStatus() == null ? null : order.getStatus().name());
        return dto;
    }

    /**
     * @param subtotal     sum of the order's own line totals
     * @param shippingCost delivery for the method selected on the order
     * @param vatAmount    VAT on the subtotal
     * @param history      status timeline, newest first
     */
    public AdminOrderDetailDto toDetailDto(OrderEntity order,
                                           BigDecimal subtotal,
                                           BigDecimal shippingCost,
                                           BigDecimal vatAmount,
                                           List<OrderStatusHistoryEntity> history)
    {
        if (order == null) {
            return null;
        }

        AdminOrderDetailDto dto = new AdminOrderDetailDto();
        dto.setId(order.getId() == null ? null : order.getId().toString());
        dto.setReference(order.getReference());
        dto.setCustomerName(order.getPlacedByName());
        dto.setCustomerEmail(customerEmail(order));
        dto.setPlacedAt(order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
        dto.setItemCount(itemCount(order));
        dto.setTotal(order.getTotalAmount());
        dto.setStatus(order.getStatus() == null ? null : order.getStatus().name());

        dto.setShippingAddress(toAddressDto(order));
        dto.setLineItems(toLineItemDtos(order));

        dto.setSubtotal(subtotal);
        dto.setShippingCost(shippingCost);
        dto.setVatAmount(vatAmount);
        // The grand total is the amount persisted on the order — what the shopper
        // was actually charged — not a re-derived sum, so the breakdown can never
        // silently disagree with the money that changed hands.
        dto.setGrandTotal(order.getTotalAmount());

        dto.setStatusHistory(toStatusHistoryDtos(history));
        return dto;
    }

    private AdminOrderAddressDto toAddressDto(OrderEntity order)
    {
        AdminOrderAddressDto address = new AdminOrderAddressDto();
        address.setStreet(order.getStreetAddress());
        address.setCity(order.getCity());
        address.setProvince(order.getProvince());
        address.setPostalCode(order.getPostalCode());
        return address;
    }

    private List<AdminOrderLineItemDto> toLineItemDtos(OrderEntity order)
    {
        List<AdminOrderLineItemDto> lineItems = new ArrayList<>();
        if (order.getItems() == null) {
            return lineItems;
        }

        for (OrderItemEntity item : order.getItems()) {
            if (item == null) {
                continue;
            }
            lineItems.add(toLineItemDto(item));
        }
        return lineItems;
    }

    private AdminOrderLineItemDto toLineItemDto(OrderItemEntity item)
    {
        AdminOrderLineItemDto dto = new AdminOrderLineItemDto();
        dto.setId(item.getId() == null ? null : item.getId().toString());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setQuantity(item.getQuantity() == null ? 0 : item.getQuantity());
        dto.setLineTotal(item.getUnitPrice() == null || item.getQuantity() == null
                ? BigDecimal.ZERO
                : item.getSubtotal());

        ProductVariantEntity variant = item.getVariant();
        if (variant != null) {
            dto.setVariantSku(variant.getSku());
            dto.setThumbnailUrl(thumbnailUrl(variant));
            if (variant.getProduct() != null) {
                dto.setProductName(variant.getProduct().getName());
            }
        }
        return dto;
    }

    /**
     * A variant deleted from the catalogue leaves its order lines intact, so a
     * missing variant is a normal state here, not an error.
     */
    private String thumbnailUrl(ProductVariantEntity variant)
    {
        if (variant.getImages() == null || variant.getImages().isEmpty()) {
            return null;
        }

        ProductImageEntity chosen = null;
        for (ProductImageEntity image : variant.getImages()) {
            if (image == null) {
                continue;
            }
            if (Boolean.TRUE.equals(image.getIsFeatured())) {
                chosen = image;
                break;
            }
            if (chosen == null) {
                chosen = image;
            }
        }
        return chosen == null ? null : chosen.getImageUrl();
    }

    private List<AdminOrderStatusHistoryDto> toStatusHistoryDtos(List<OrderStatusHistoryEntity> history)
    {
        List<AdminOrderStatusHistoryDto> entries = new ArrayList<>();
        if (history == null) {
            return entries;
        }

        for (OrderStatusHistoryEntity entry : history) {
            if (entry == null) {
                continue;
            }
            AdminOrderStatusHistoryDto dto = new AdminOrderStatusHistoryDto();
            dto.setStatus(entry.getStatus() == null ? null : entry.getStatus().name());
            LocalDateTime createdAt = entry.getCreatedAt();
            dto.setTimestamp(createdAt == null ? null : createdAt.toString());
            dto.setStaffName(entry.getChangedBy());
            dto.setComment(entry.getComment());
            entries.add(dto);
        }
        return entries;
    }

    private String customerEmail(OrderEntity order)
    {
        if (order.getCustomerEntity() != null && order.getCustomerEntity().getUser() != null) {
            String email = order.getCustomerEntity().getUser().getEmail();
            if (email != null && !email.isBlank()) {
                return email;
            }
        }
        return order.getContactEmail();
    }

    private int itemCount(OrderEntity order)
    {
        if (order.getItems() == null) {
            return 0;
        }
        int count = 0;
        for (OrderItemEntity item : order.getItems()) {
            if (item != null && item.getQuantity() != null) {
                count += item.getQuantity();
            }
        }
        return count;
    }
}
