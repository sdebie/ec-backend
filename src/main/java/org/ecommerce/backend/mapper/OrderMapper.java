package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.dto.ProductVariantDetailDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
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
 * Maps orders into the shopper-facing shapes — a customer's own view of their order.
 * The staff view is {@link OrderAdminMapper}'s job.
 * <p>
 * Pure — no database access. The status timeline is passed in as {@code @Context} because a
 * mapper must not open queries; {@code OrderService} loads it.
 * <p>
 * ⚠️ {@code itemCount} means different things on the two shapes and both are deliberate:
 * {@link OrderResponseDto} counts distinct lines, {@link OrderSummaryDto} sums quantities.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, uses = {ProductMapper.class, TimestampMapper.class},
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface OrderMapper
{
    @Mapping(target = "createDate", source = "createdAt")
    @Mapping(target = "customer", source = "customerEntity")
    @Mapping(target = "itemCount", ignore = true)
    OrderResponseDto toResponseDto(OrderEntity order);

    /**
     * Uses {@link ProductVariantDetailDto} (reduced variant — no sku/status/prices) with
     * {@code ProductDetailDto} (name only) as the nested product reference.
     */
    OrderItemDetailDto toItemDetailDto(OrderItemEntity item);

    ProductVariantDetailDto toVariantDetailDto(ProductVariantEntity variant);

    @Mapping(target = "email", source = "user.email")
    CustomerDto toCustomerDto(CustomerEntity customer);

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
    // A customer row with no user carries no address to show, so it reads as no customer at
    // all rather than as a customer whose email is null.
    @Mapping(target = "customerEntity",
            expression = "java(order.getCustomerEntity() != null && order.getCustomerEntity().getUser() != null "
                    + "? toCustomerDto(order.getCustomerEntity()) : null)")
    @Mapping(target = "statusHistory", expression = "java(toStatusHistoryDtos(history))")
    OrderDetailRespDto toDetailDto(OrderEntity order, @Context List<OrderStatusHistoryEntity> history);

    OrderDetailRespDto.OrderStatusHistoryDetailRespDto toStatusHistoryDto(OrderStatusHistoryEntity entry);

    List<OrderDetailRespDto.OrderStatusHistoryDetailRespDto> toStatusHistoryDtos(List<OrderStatusHistoryEntity> history);

    @Mapping(target = "orderDate", source = "createdAt")
    @Mapping(target = "itemCount", expression = "java(order.totalUnits())")
    OrderSummaryDto toSummaryDto(OrderEntity order);

    /** Distinct line count — {@link OrderSummaryDto} deliberately counts units instead. */
    @AfterMapping
    default void countLines(@MappingTarget OrderResponseDto dto)
    {
        dto.setItemCount(dto.getItems() == null ? 0 : dto.getItems().size());
    }

    /** An absent timeline reads as empty, never as a null the client has to guard. */
    @AfterMapping
    default void defaultCollectionsToEmpty(@MappingTarget OrderDetailRespDto dto)
    {
        if (dto.getStatusHistory() == null) {
            dto.setStatusHistory(new ArrayList<>());
        }
        if (dto.getItems() == null) {
            dto.setItems(new ArrayList<>());
        }
    }

    /** A variant with no images reads as an empty gallery, not a null one. */
    @AfterMapping
    default void defaultImagesToEmpty(@MappingTarget ProductVariantDetailDto dto)
    {
        if (dto.getImages() == null) {
            dto.setImages(new ArrayList<>());
        }
    }


}
