package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class OrderMapper
{
    @Inject
    ProductMapper productMapper;

    public OrderResponseDto toResponseDto(OrderEntity orderEntity)
    {
        if (orderEntity == null) {
            return null;
        }

        OrderResponseDto dto = new OrderResponseDto();
        dto.setId(orderEntity.getId() == null ? null : orderEntity.getId().toString());
        dto.setSessionId(orderEntity.getSessionId() == null ? null : orderEntity.getSessionId().toString());
        dto.setStatus(orderEntity.getStatus() == null ? null : orderEntity.getStatus().name());
        dto.setCreateDate(orderEntity.getCreatedAt() == null ? null : orderEntity.getCreatedAt().toString());
        dto.setTotalAmount(orderEntity.getTotalAmount());
        dto.setCustomer(toCustomerDto(orderEntity.getCustomerEntity()));

        if (orderEntity.getItems() != null) {
            dto.setItems(new ArrayList<>(orderEntity.getItems().size()));
            for (OrderItemEntity item : orderEntity.getItems()) {
                OrderItemDetailDto itemDto = toItemDetailDto(item);
                if (itemDto != null) {
                    dto.getItems().add(itemDto);
                }
            }
        }

        dto.setItemCount(dto.getItems() == null ? 0 : dto.getItems().size());
        return dto;
    }

    /**
     * Maps an OrderItemEntity to the canonical order-item output DTO.
     * Uses ProductVariantDetailDto (reduced variant — no sku/status/prices)
     * with ProductDetailDto (name only) as the nested product reference.
     */
    private OrderItemDetailDto toItemDetailDto(OrderItemEntity orderItemEntity)
    {
        if (orderItemEntity == null) {
            return null;
        }

        OrderItemDetailDto dto = new OrderItemDetailDto();
        dto.setId(orderItemEntity.getId() == null ? null : orderItemEntity.getId().toString());
        dto.setUnitPrice(orderItemEntity.getUnitPrice());
        dto.setQuantity(orderItemEntity.getQuantity());

        if (orderItemEntity.getVariant() != null) {
            ProductVariantDetailDto variantDetailDto = new ProductVariantDetailDto();
            variantDetailDto.setId(orderItemEntity.getVariant().getId());
            variantDetailDto.setStockQuantity(orderItemEntity.getVariant().getStockQuantity());
            variantDetailDto.setAttributesJson(orderItemEntity.getVariant().getAttributesJson());
            variantDetailDto.setWeightKg(orderItemEntity.getVariant().getWeightKg());

            if (orderItemEntity.getVariant().getProduct() != null) {
                ProductDetailDto productDetailDto = new ProductDetailDto();
                productDetailDto.setName(orderItemEntity.getVariant().getProduct().getName());
                variantDetailDto.setProduct(productDetailDto);
            }

            variantDetailDto.setImages(toImageDtos(orderItemEntity.getVariant().getImages()));
            dto.setVariant(variantDetailDto);
        }

        return dto;
    }

    private List<ProductImageDto> toImageDtos(List<ProductImageEntity> images)
    {
        if (images == null || images.isEmpty()) {
            return Collections.emptyList();
        }
        // Single source of truth for ProductImageEntity → ProductImageDto.
        List<ProductImageDto> result = new ArrayList<>(images.size());
        for (ProductImageEntity img : images) {
            if (img == null) continue;
            result.add(productMapper.mapImageEntityToDto(img));
        }
        return result;
    }

    public CustomerDto toCustomerDto(CustomerEntity customer)
    {
        if (customer == null) {
            return null;
        }

        CustomerDto dto = new CustomerDto();
        dto.setEmail(customer.getUser() != null ? customer.getUser().getEmail() : null);
        return dto;
    }

    /**
     * Maps an OrderEntity to an OrderDetailRespDto including nested items,
     * variant details, images, and status history.
     * The status history is queried via Panache.
     */
    public OrderDetailRespDto toDetailDto(OrderEntity orderEntity)
    {
        if (orderEntity == null) {
            return null;
        }

        OrderDetailRespDto detail = new OrderDetailRespDto();

        // Map OrderEntity fields
        detail.setId(orderEntity.getId());
        detail.setTotalAmount(orderEntity.getTotalAmount());
        detail.setSessionId(orderEntity.getSessionId());
        detail.setStatus(orderEntity.getStatus());
        detail.setShippingPhone(null); // Legacy field — no longer on OrderEntity
        detail.setShippingAddressLine1(orderEntity.getStreetAddress());
        detail.setShippingAddressLine2(null); // Legacy field — merged into streetAddress
        detail.setShippingCity(orderEntity.getCity());
        detail.setShippingProvince(orderEntity.getProvince());
        detail.setShippingPostalCode(orderEntity.getPostalCode());
        detail.setCreatedAt(orderEntity.getCreatedAt());

        // Customer reference — same canonical CustomerDto as toResponseDto
        if (orderEntity.getCustomerEntity() != null && orderEntity.getCustomerEntity().getUser() != null) {
            detail.setCustomerEntity(toCustomerDto(orderEntity.getCustomerEntity()));
        }

        // Items — same canonical DTO as toResponseDto
        if (orderEntity.getItems() != null) {
            detail.setItems(new ArrayList<>());
            for (OrderItemEntity orderItemEntity : orderEntity.getItems()) {
                OrderItemDetailDto itemDetailDto = toItemDetailDto(orderItemEntity);
                if (itemDetailDto != null) {
                    detail.getItems().add(itemDetailDto);
                }
            }
        }

        // Status history (Panache query)
        List<OrderStatusHistoryEntity> histories = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", orderEntity.getId())
                .list();

        if (histories != null) {
            for (OrderStatusHistoryEntity history : histories) {
                if (history == null) {
                    continue;
                }
                OrderDetailRespDto.OrderStatusHistoryDetailRespDto historyDto = new OrderDetailRespDto.OrderStatusHistoryDetailRespDto();
                historyDto.setId(history.getId());
                historyDto.setStatus(history.getStatus());
                historyDto.setComment(history.getComment());
                historyDto.setChangedBy(history.getChangedBy());
                historyDto.setCreatedAt(history.getCreatedAt());
                detail.getStatusHistory().add(historyDto);
            }
        }

        return detail;
    }

    /**
     * Maps an OrderEntity to an OrderSummaryDto.
     * Computes itemCount as the sum of quantities across all line items.
     */
    public OrderSummaryDto toSummaryDto(OrderEntity orderEntity)
    {
        if (orderEntity == null) {
            return null;
        }

        OrderSummaryDto dto = new OrderSummaryDto();
        dto.setId(orderEntity.getId() != null ? orderEntity.getId().toString() : null);
        dto.setOrderDate(orderEntity.getCreatedAt() != null ? orderEntity.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        dto.setStatus(orderEntity.getStatus() != null ? orderEntity.getStatus().name() : null);
        dto.setItemCount(orderEntity.getItems() != null ? orderEntity.getItems()
                .stream()
                .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum() : 0);

        dto.setTotalAmount(orderEntity.getTotalAmount() != null ? orderEntity.getTotalAmount().doubleValue() : 0.0);
        return dto;
    }
}
