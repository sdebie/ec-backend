package org.ecommerce.backend.mapper;

import org.ecommerce.backend.service.OrderTotals;
import org.ecommerce.common.dto.AdminOrderAddressDto;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderLineItemDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.AdminOrderPaymentDto;
import org.ecommerce.common.dto.AdminOrderStatusHistoryDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.PaymentLogEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.repository.ProductImageRepository;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.ecommerce.backend.utils.CollectionUtils.emptyIfNull;
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
     * @param totals        the money the service computed for this order
     * @param history       status timeline, newest first
     * @param latestPayment the most recent gateway callback for this order, or null
     *                      if none has been recorded yet
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
    @Mapping(target = "latestPayment", expression = "java(toPaymentDto(latestPayment))")
    AdminOrderDetailDto toDetailDto(OrderEntity order,
                                    @Context OrderTotals totals,
                                    @Context List<OrderStatusHistoryEntity> history,
                                    @Context PaymentLogEntity latestPayment,
                                    @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    /**
     * {@code thumbnailUrl} is filled by {@link #attachThumbnail} from the caller-supplied
     * {@code imagesByVariantId} rather than {@code item.getVariant().displayImageUrl()} — that
     * reads the variant's own managed {@code images} collection, a different aggregate this
     * mapping must never load or touch.
     */
    @Mapping(target = "productName", source = "variant.product.name")
    @Mapping(target = "variantSku", source = "variant.sku")
    @Mapping(target = "thumbnailUrl", ignore = true)
    @Mapping(target = "lineTotal", source = "subtotal")
    AdminOrderLineItemDto toLineItemDto(OrderItemEntity item, @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    List<AdminOrderLineItemDto> toLineItemDtos(List<OrderItemEntity> items, @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    @Mapping(target = "timestamp", source = "createdAt")
    @Mapping(target = "staffName", source = "changedBy")
    AdminOrderStatusHistoryDto toStatusHistoryDto(OrderStatusHistoryEntity entry);

    List<AdminOrderStatusHistoryDto> toStatusHistoryDtos(List<OrderStatusHistoryEntity> history);

    @Mapping(target = "street", source = "streetAddress")
    AdminOrderAddressDto toAddressDto(OrderEntity order);

    @Mapping(target = "gateway", source = "gatewayName")
    @Mapping(target = "receivedAt", source = "createdAt")
    AdminOrderPaymentDto toPaymentDto(PaymentLogEntity log);

    /**
     * An absent timeline or line-item set reads as empty, never as a null the client has to
     * guard. The DTO initialises both, but the status-history mapping assigns
     * unconditionally, so a null history would otherwise overwrite that initial empty list.
     */
    @AfterMapping
    default void defaultCollectionsToEmpty(@MappingTarget AdminOrderDetailDto dto)
    {
        dto.setStatusHistory(emptyIfNull(dto.getStatusHistory()));
        dto.setLineItems(emptyIfNull(dto.getLineItems()));
    }

    /**
     * The featured image if the variant has one, else the first by sort order — same rule as
     * {@code ProductVariantEntity.displayImageUrl()}, but read from the caller-supplied map:
     * {@link ProductImageRepository#findGroupedByVariantIds} already orders each variant's list
     * featured-first then by sort order, so the first element is always the right pick.
     */
    @AfterMapping
    default void attachThumbnail(OrderItemEntity item, @MappingTarget AdminOrderLineItemDto dto,
                                 @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId)
    {
        UUID variantId = item.getVariant() == null ? null : item.getVariant().getId();
        List<ProductImageEntity> images = variantId == null || imagesByVariantId == null
                ? List.of()
                : imagesByVariantId.getOrDefault(variantId, List.of());
        dto.setThumbnailUrl(images.isEmpty() ? null : images.get(0).getImageUrl());
    }

}
