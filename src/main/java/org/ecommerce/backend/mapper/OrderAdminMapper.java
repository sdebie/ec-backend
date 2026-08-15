package org.ecommerce.backend.mapper;

import org.ecommerce.backend.service.OrderTotals;
import org.ecommerce.common.dto.AdminOrderAddressDto;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderLineItemDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.AdminOrderStatusHistoryDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.ArrayList;
import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps orders into the admin-facing shapes, the same split {@code CustomerAdminMapper}
 * makes for customers: the storefront's {@code OrderMapper} answers a shopper's own
 * questions about their order, this one answers staff's.
 * <p>
 * Pure — no database access and no derivation. Anything computed comes from the entity
 * ({@code totalUnits}, {@code reachableEmail}, {@code displayImageUrl}) or from the
 * {@link OrderTotals} the service already produced.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, uses = TimestampMapper.class, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface OrderAdminMapper
{
    @Mapping(target = "customerName", source = "placedByName")
    @Mapping(target = "placedAt", source = "createdAt")
    @Mapping(target = "total", source = "totalAmount")
    @Mapping(target = "itemCount", expression = "java(order.totalUnits())")
    AdminOrderListItemDto toListItemDto(OrderEntity order);

    /**
     * @param totals  the money the service computed for this order
     * @param history status timeline, newest first
     */
    @Mapping(target = "customerName", source = "placedByName")
    @Mapping(target = "placedAt", source = "createdAt")
    @Mapping(target = "total", source = "totalAmount")
    @Mapping(target = "itemCount", expression = "java(order.totalUnits())")
    @Mapping(target = "customerEmail", expression = "java(order.reachableEmail())")
    @Mapping(target = "shippingAddress", source = "order")
    @Mapping(target = "lineItems", source = "items")
    @Mapping(target = "subtotal", expression = "java(totals.subtotal())")
    @Mapping(target = "shippingCost", expression = "java(totals.shippingEstimate())")
    @Mapping(target = "vatAmount", expression = "java(totals.vatAmount())")
    // The grand total is the amount persisted on the order — what the shopper was actually
    // charged — not a re-derived sum, so the breakdown can never silently disagree with the
    // money that changed hands.
    @Mapping(target = "grandTotal", source = "totalAmount")
    @Mapping(target = "statusHistory", expression = "java(toStatusHistoryDtos(history))")
    AdminOrderDetailDto toDetailDto(OrderEntity order,
                                    @Context OrderTotals totals,
                                    @Context List<OrderStatusHistoryEntity> history);

    @Mapping(target = "productName", source = "variant.product.name")
    @Mapping(target = "variantSku", source = "variant.sku")
    @Mapping(target = "thumbnailUrl", expression = "java(item.getVariant() == null ? null : item.getVariant().displayImageUrl())")
    @Mapping(target = "lineTotal", source = "subtotal")
    AdminOrderLineItemDto toLineItemDto(OrderItemEntity item);

    List<AdminOrderLineItemDto> toLineItemDtos(List<OrderItemEntity> items);

    @Mapping(target = "timestamp", source = "createdAt")
    @Mapping(target = "staffName", source = "changedBy")
    AdminOrderStatusHistoryDto toStatusHistoryDto(OrderStatusHistoryEntity entry);

    List<AdminOrderStatusHistoryDto> toStatusHistoryDtos(List<OrderStatusHistoryEntity> history);

    @Mapping(target = "street", source = "streetAddress")
    AdminOrderAddressDto toAddressDto(OrderEntity order);

    /**
     * An absent timeline or line-item set reads as empty, never as a null the client has to
     * guard. The DTO initialises both, but the status-history mapping assigns
     * unconditionally, so a null history would otherwise overwrite that initial empty list.
     */
    @AfterMapping
    default void defaultCollectionsToEmpty(@MappingTarget AdminOrderDetailDto dto)
    {
        if (dto.getStatusHistory() == null) {
            dto.setStatusHistory(new ArrayList<>());
        }
        if (dto.getLineItems() == null) {
            dto.setLineItems(new ArrayList<>());
        }
    }

}
