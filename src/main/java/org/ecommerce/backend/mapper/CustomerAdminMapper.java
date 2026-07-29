package org.ecommerce.backend.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.dto.AdminOrderRefDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;

import java.util.List;

/**
 * Hand-written CDI mapper for CustomerAdminService entity→DTO mappings.
 * <p>
 * This is NOT a MapStruct @Mapper interface because {@link #toListItemDto(CustomerEntity)}
 * runs a Panache query to fetch the customer's wholesale application — something a
 * MapStruct interface cannot do.
 * <p>
 * Pure field-copy parts delegate to {@link WholesaleMapper} for the nested
 * {@code WholesaleApplicationDetailsDto}.
 */
@ApplicationScoped
public class CustomerAdminMapper
{

    @Inject
    WholesaleMapper wholesaleMapper;

    // ── Query-bearing: fetches wholesale application via Panache ─────────────

    /**
     * Maps a customer entity to a list-item DTO, fetching the wholesale application
     * from the database.
     */
    public AdminCustomerListItemDto toListItemDto(CustomerEntity customerEntity)
    {
        WholesaleApplicationEntity app = WholesaleApplicationEntity
                .find("customer.id = ?1", customerEntity.getId())
                .firstResult();
        return toListItemDto(customerEntity, app);
    }

    // ── Pure field-copy methods ─────────────────────────────────────────────

    /**
     * Maps a customer entity and a pre-fetched wholesale application to a list-item DTO.
     * Pure — no database access.
     */
    public AdminCustomerListItemDto toListItemDto(CustomerEntity customerEntity, WholesaleApplicationEntity app)
    {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.setId(customerEntity.getId().toString());
        dto.setFirstName(customerEntity.getFirstName());
        dto.setLastName(customerEntity.getLastName());
        dto.setEmail(customerEntity.getUser() != null ? customerEntity.getUser().getEmail() : null);
        dto.setStatus(customerEntity.getStatus() != null ? customerEntity.getStatus().name() : null);
        dto.setShopperType(customerEntity.getShopperType() != null ? customerEntity.getShopperType().name() : null);
        dto.setRegisteredAt(customerEntity.getUser() != null && customerEntity.getUser().getCreatedAt() != null ? customerEntity.getUser().getCreatedAt().toString() : null);
        dto.setWholesaleApplicationStatus(app != null && app.getStatus() != null ? app.getStatus().name() : null);
        return dto;
    }

    /**
     * Maps a customer entity, wholesale application, and recent orders to a detail DTO.
     * Pure — no database access; delegates wholesale application mapping to WholesaleMapper.
     */
    public AdminCustomerDetailDto toDetailDto(CustomerEntity customerEntity, WholesaleApplicationEntity app, List<OrderEntity> orders)
    {
        AdminCustomerDetailDto dto = new AdminCustomerDetailDto();
        dto.setId(customerEntity.getId().toString());
        dto.setFirstName(customerEntity.getFirstName());
        dto.setLastName(customerEntity.getLastName());
        dto.setEmail(customerEntity.getUser() != null ? customerEntity.getUser().getEmail() : null);
        dto.setPhone(customerEntity.getPhone());
        dto.setStatus(customerEntity.getStatus() != null ? customerEntity.getStatus().name() : null);
        dto.setShopperType(customerEntity.getShopperType() != null ? customerEntity.getShopperType().name() : null);
        dto.setRegisteredAt(customerEntity.getUser() != null && customerEntity.getUser().getCreatedAt() != null ? customerEntity.getUser().getCreatedAt().toString() : null);

        dto.setWholesaleApplication(app != null ? wholesaleMapper.toDetailsDto(app) : null);

        dto.setRecentOrders(orders.stream()
                .map(this::toOrderRefDto)
                .toList());

        return dto;
    }

    /**
     * Maps an order entity to an order reference DTO.
     * Pure — no database access.
     */
    public AdminOrderRefDto toOrderRefDto(OrderEntity orderEntity)
    {
        AdminOrderRefDto dto = new AdminOrderRefDto();
        dto.setId(orderEntity.getId().toString());
        dto.setReference("ORD-" + orderEntity.getId().toString().substring(0, 8).toUpperCase());
        dto.setPlacedAt(orderEntity.getCreatedAt() != null ? orderEntity.getCreatedAt().toString() : null);
        dto.setTotal(orderEntity.getTotalAmount() != null ? orderEntity.getTotalAmount().doubleValue() : 0.0);
        dto.setStatus(orderEntity.getStatus() != null ? orderEntity.getStatus().name() : null);
        return dto;
    }
}
