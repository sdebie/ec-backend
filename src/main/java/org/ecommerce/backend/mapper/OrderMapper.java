package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.CustomerDetailDto;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.ImageDetailDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderItemResponseDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.OrderSummaryDto;
import org.ecommerce.common.dto.ProductDetailDto;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductVariantDetailDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrderMapper
{
    @Inject
    ProductMapper productMapper;

    public OrderResponseDto toResponseDto(OrderEntity entity)
    {
        if (entity == null) {
            return null;
        }

        OrderResponseDto dto = new OrderResponseDto();
        dto.id = entity.id == null ? null : entity.id.toString();
        dto.sessionId = entity.sessionId == null ? null : entity.sessionId.toString();
        dto.status = entity.status == null ? null : entity.status.name();
        dto.createDate = entity.createdAt == null ? null : entity.createdAt.toString();
        dto.totalAmount = entity.totalAmount;
        dto.customer = toCustomerDto(entity.customerEntity);

        if (entity.items != null) {
            dto.items = new ArrayList<>(entity.items.size());
            for (OrderItemEntity item : entity.items) {
                OrderItemResponseDto itemDto = toItemDto(item);
                if (itemDto != null) {
                    dto.items.add(itemDto);
                }
            }
        }

        dto.itemCount = dto.items == null ? 0 : dto.items.size();

        return dto;
    }

    private OrderItemResponseDto toItemDto(OrderItemEntity item)
    {
        if (item == null) {
            return null;
        }

        OrderItemResponseDto dto = new OrderItemResponseDto();
        dto.id = item.id == null ? null : item.id.toString();
        dto.unitPrice = item.unitPrice;
        dto.quantity = item.quantity;
        dto.variant = toVariantDto(item.variant);
        return dto;
    }

    private ProductVariantDto toVariantDto(ProductVariantEntity variant)
    {
        if (variant == null) {
            return null;
        }

        ProductVariantDto dto = new ProductVariantDto();
        dto.id = variant.id == null ? null : variant.id.toString();
        dto.stockQuantity = variant.stockQuantity;
        dto.attributesJson = variant.attributesJson;
        dto.weightKg = variant.weightKg;
        dto.product = toProductDto(variant.product);
        dto.images = toImageDtos(variant.images);
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

    private ProductDto toProductDto(ProductEntity product)
    {
        if (product == null) {
            return null;
        }

        ProductDto dto = new ProductDto();
        dto.id = product.id == null ? null : product.id.toString();
        dto.name = product.name;
        return dto;
    }

    public CustomerDto toCustomerDto(CustomerEntity customer)
    {
        if (customer == null) {
            return null;
        }

        CustomerDto dto = new CustomerDto();
        dto.setEmail(customer.user != null ? customer.user.email : null);
        return dto;
    }

    /**
     * Maps an OrderEntity to an OrderDetailRespDto including nested items,
     * variant details, images, and status history.
     * The status history is queried via Panache.
     */
    public OrderDetailRespDto toDetailDto(OrderEntity order)
    {
        if (order == null) {
            return null;
        }

        OrderDetailRespDto detail = new OrderDetailRespDto();

        // Map OrderEntity fields
        detail.id = order.id;
        detail.totalAmount = order.totalAmount;
        detail.sessionId = order.sessionId;
        detail.status = order.status;
        detail.shippingPhone = null; // Legacy field — no longer on OrderEntity
        detail.shippingAddressLine1 = order.streetAddress;
        detail.shippingAddressLine2 = null; // Legacy field — merged into streetAddress
        detail.shippingCity = order.city;
        detail.shippingProvince = order.province;
        detail.shippingPostalCode = order.postalCode;
        detail.createdAt = order.createdAt;

        // Customer detail
        if (order.customerEntity != null && order.customerEntity.user != null) {
            CustomerDetailDto customerDetail = new CustomerDetailDto();
            customerDetail.email = order.customerEntity.user.email;
            detail.customerEntity = customerDetail;
        }

        // Items
        if (order.items != null) {
            detail.items = new ArrayList<>();
            for (OrderItemEntity orderItemEntity : order.items) {
                OrderItemDetailDto itemDetailDto = toItemDetailDto(orderItemEntity);
                if (itemDetailDto != null) {
                    detail.items.add(itemDetailDto);
                }
            }
        }

        // Status history (Panache query)
        List<OrderStatusHistoryEntity> histories = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", order.id)
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

    private OrderItemDetailDto toItemDetailDto(OrderItemEntity item)
    {
        if (item == null) {
            return null;
        }

        OrderItemDetailDto dto = new OrderItemDetailDto();
        dto.id = item.id;
        dto.unitPrice = item.unitPrice;
        dto.quantity = item.quantity;

        if (item.variant != null) {
            ProductVariantDetailDto variantDetailDto = new ProductVariantDetailDto();
            variantDetailDto.id = item.variant.id;
            variantDetailDto.stockQuantity = item.variant.stockQuantity;
            variantDetailDto.attributesJson = item.variant.attributesJson;
            variantDetailDto.weightKg = item.variant.weightKg;

            if (item.variant.product != null) {
                ProductDetailDto productDetailDto = new ProductDetailDto();
                productDetailDto.name = item.variant.product.name;
                variantDetailDto.product = productDetailDto;
            }

            if (item.variant.images != null) {
                List<ImageDetailDto> imageDetailDtos = new ArrayList<>();
                for (ProductImageEntity imageEntity : item.variant.images) {
                    ImageDetailDto imageDetailDto = new ImageDetailDto();
                    imageDetailDto.id = imageEntity.id;
                    imageDetailDto.imageUrl = imageEntity.imageUrl;
                    imageDetailDto.sortOrder = imageEntity.sortOrder;
                    imageDetailDtos.add(imageDetailDto);
                }
                variantDetailDto.images = imageDetailDtos;
            }
            dto.variant = variantDetailDto;
        }

        return dto;
    }

    /**
     * Maps an OrderEntity to an OrderSummaryDto.
     * Computes itemCount as the sum of quantities across all line items.
     */
    public OrderSummaryDto toSummaryDto(OrderEntity order)
    {
        if (order == null) {
            return null;
        }

        OrderSummaryDto dto = new OrderSummaryDto();
        dto.id = order.id != null ? order.id.toString() : null;
        dto.orderDate = order.createdAt != null
                ? order.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;
        dto.status = order.status != null ? order.status.name() : null;
        dto.itemCount = order.items != null
                ? order.items.stream()
                    .mapToInt(item -> item.quantity != null ? item.quantity : 0)
                    .sum()
                : 0;
        dto.totalAmount = order.totalAmount != null
                ? order.totalAmount.doubleValue()
                : 0.0;
        return dto;
    }
}
