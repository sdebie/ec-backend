package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.OrderItemResponseDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.ProductDto;
import org.ecommerce.common.dto.ProductImageDto;
import org.ecommerce.common.dto.ProductVariantDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class OrderMapper
{
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
        List<ProductImageDto> result = new ArrayList<>(images.size());
        for (ProductImageEntity img : images) {
            if (img == null) continue;
            result.add(new ProductImageDto(
                    img.id == null ? null : img.id.toString(),
                    img.imageUrl,
                    img.sortOrder,
                    Boolean.TRUE.equals(img.isFeatured)
            ));
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

    private CustomerDto toCustomerDto(CustomerEntity customer)
    {
        if (customer == null) {
            return null;
        }

        CustomerDto dto = new CustomerDto();
        dto.setEmail(customer.email);
        return dto;
    }
}
