package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.OrderDetailDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductImageEntity;
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
 * Maps orders into the shopper-facing shapes — a customer's own view of their order.
 * The staff view is {@link OrderAdminMapper}'s job.
 * <p>
 * Pure — no database access. The status timeline is passed in as {@code @Context} because a
 * mapper must not open queries; {@code OrderService} loads it.
 * <p>
 * ⚠️ {@code itemCount} means different things on the two shapes and both are deliberate:
 * {@code toOrderDto} counts distinct lines, {@link OrderSummaryDto} sums quantities.
 * <p>
 * ⚠️ {@code toOrderDto}, {@code toDetailDto}, and {@code toStatusDto} all return
 * {@link OrderDetailDto} — one shape, three resolvers, each populating only what its caller
 * needs. {@code sessionId} is the guest-checkout credential: only {@code toOrderDto} may
 * populate it. {@code toDetailDto} and {@code toStatusDto} both explicitly ignore it, and
 * that ignore is load-bearing — {@code OrderEntity.sessionId} would otherwise auto-map by name.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, uses = {ProductMapper.class, TimestampMapper.class},
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface OrderMapper
{
    @Mapping(target = "sessionId", source = "sessionId")
    @Mapping(target = "customerEmail", source = "customerEntity.user.email")
    @Mapping(target = "itemCount", expression = "java(order.getItems() == null ? 0 : order.getItems().size())")
    @Mapping(target = "shippingPhone", ignore = true)
    @Mapping(target = "shippingAddressLine1", ignore = true)
    @Mapping(target = "shippingAddressLine2", ignore = true)
    @Mapping(target = "shippingCity", ignore = true)
    @Mapping(target = "shippingProvince", ignore = true)
    @Mapping(target = "shippingPostalCode", ignore = true)
    @Mapping(target = "statusHistory", ignore = true)
    OrderDetailDto toOrderDto(OrderEntity order, @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    /**
     * Flattens the line's variant (and its product name) directly onto {@link OrderItemDetailDto}
     * — no nested variant/product DTO. {@code images} is filled by {@link #attachImages} from the
     * caller-supplied {@code imagesByVariantId} instead of {@code variant.getImages()} — this runs
     * on orders, a different aggregate, and must never load or touch the variant's own managed
     * collection.
     */
    @Mapping(target = "variantId", source = "variant.id")
    @Mapping(target = "stockQuantity", source = "variant.stockQuantity")
    @Mapping(target = "attributesJson", source = "variant.attributesJson")
    @Mapping(target = "weightKg", source = "variant.weightKg")
    @Mapping(target = "productName", source = "variant.product.name")
    @Mapping(target = "images", ignore = true)
    OrderItemDetailDto toItemDetailDto(OrderItemEntity item, @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    /**
     * @param history status timeline, newest first — loaded by the caller, not queried here
     */
    @Mapping(target = "shippingAddressLine1", source = "streetAddress")
    @Mapping(target = "shippingCity", source = "city")
    @Mapping(target = "shippingProvince", source = "province")
    @Mapping(target = "shippingPostalCode", source = "postalCode")
    // Legacy fields: shippingPhone is no longer on OrderEntity, and the second address line
    // was merged into streetAddress. Both stay absent rather than being invented.
    @Mapping(target = "shippingPhone", ignore = true)
    @Mapping(target = "shippingAddressLine2", ignore = true)
    @Mapping(target = "customerEmail", source = "customerEntity.user.email")
    @Mapping(target = "statusHistory", expression = "java(toStatusHistoryDtos(history))")
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "itemCount", ignore = true)
    OrderDetailDto toDetailDto(OrderEntity order, @Context List<OrderStatusHistoryEntity> history,
                               @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId);

    OrderDetailDto.OrderStatusHistoryDto toStatusHistoryDto(OrderStatusHistoryEntity entry);

    List<OrderDetailDto.OrderStatusHistoryDto> toStatusHistoryDtos(List<OrderStatusHistoryEntity> history);

    @Mapping(target = "orderDate", source = "createdAt")
    @Mapping(target = "itemCount", expression = "java(order.totalUnits())")
    OrderSummaryDto toSummaryDto(OrderEntity order);

    /**
     * The guest checkout success-page poll.
     * Populates only what the page renders: id, status, total, creation time. Every other field
     * on the shared {@link OrderDetailDto} shape — sessionId very much included — is ignored,
     * not merely left to chance.
     */
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "customerEmail", ignore = true)
    @Mapping(target = "itemCount", ignore = true)
    @Mapping(target = "shippingPhone", ignore = true)
    @Mapping(target = "shippingAddressLine1", ignore = true)
    @Mapping(target = "shippingAddressLine2", ignore = true)
    @Mapping(target = "shippingCity", ignore = true)
    @Mapping(target = "shippingProvince", ignore = true)
    @Mapping(target = "shippingPostalCode", ignore = true)
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "statusHistory", ignore = true)
    OrderDetailDto toStatusDto(OrderEntity order);

    /** An absent timeline or item list reads as empty, never as a null the client has to guard. */
    @AfterMapping
    default void defaultCollectionsToEmpty(@MappingTarget OrderDetailDto dto)
    {
        dto.setStatusHistory(emptyIfNull(dto.getStatusHistory()));
        dto.setItems(emptyIfNull(dto.getItems()));
    }

    List<ProductImageDto> toImageDtos(List<ProductImageEntity> images);

    /**
     * Looks up this item's variant images by id in the caller-supplied map rather than reading
     * {@code variant.getImages()} — see {@link #toItemDetailDto}. A variant with no images (or
     * none in the map, or no variant at all) reads as an empty gallery, never a null one.
     */
    @AfterMapping
    default void attachImages(OrderItemEntity item, @MappingTarget OrderItemDetailDto dto,
                              @Context Map<UUID, List<ProductImageEntity>> imagesByVariantId)
    {
        UUID variantId = item.getVariant() == null ? null : item.getVariant().getId();
        List<ProductImageEntity> images = imagesByVariantId == null || variantId == null
                ? List.of()
                : imagesByVariantId.getOrDefault(variantId, List.of());
        dto.setImages(toImageDtos(images));
    }

}
