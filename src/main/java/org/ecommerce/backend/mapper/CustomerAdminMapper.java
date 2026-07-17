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
 *
 * This is NOT a MapStruct @Mapper interface because {@link #toListItemDto(CustomerEntity)}
 * runs a Panache query to fetch the customer's wholesale application — something a
 * MapStruct interface cannot do.
 *
 * Pure field-copy parts delegate to {@link WholesaleMapper} for the nested
 * {@code WholesaleApplicationDetailsDto}.
 */
@ApplicationScoped
public class CustomerAdminMapper {

    @Inject
    WholesaleMapper wholesaleMapper;

    // ── Query-bearing: fetches wholesale application via Panache ─────────────

    /**
     * Maps a customer entity to a list-item DTO, fetching the wholesale application
     * from the database.
     */
    public AdminCustomerListItemDto toListItemDto(CustomerEntity c) {
        WholesaleApplicationEntity app = WholesaleApplicationEntity
                .find("customer.id = ?1", c.id)
                .firstResult();
        return toListItemDto(c, app);
    }

    // ── Pure field-copy methods ─────────────────────────────────────────────

    /**
     * Maps a customer entity and a pre-fetched wholesale application to a list-item DTO.
     * Pure — no database access.
     */
    public AdminCustomerListItemDto toListItemDto(CustomerEntity c, WholesaleApplicationEntity app) {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.id         = c.id.toString();
        dto.firstName  = c.firstName;
        dto.lastName   = c.lastName;
        dto.email      = c.user != null ? c.user.email : null;
        dto.status     = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;
        dto.wholesaleApplicationStatus = app != null && app.status != null
                ? app.status.name()
                : null;
        return dto;
    }

    /**
     * Maps a customer entity, wholesale application, and recent orders to a detail DTO.
     * Pure — no database access; delegates wholesale application mapping to WholesaleMapper.
     */
    public AdminCustomerDetailDto toDetailDto(CustomerEntity c,
                                              WholesaleApplicationEntity app,
                                              List<OrderEntity> orders) {
        AdminCustomerDetailDto dto = new AdminCustomerDetailDto();
        dto.id          = c.id.toString();
        dto.firstName   = c.firstName;
        dto.lastName    = c.lastName;
        dto.email       = c.user != null ? c.user.email : null;
        dto.phone       = c.phone;
        dto.status      = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;

        dto.wholesaleApplication = app != null ? wholesaleMapper.toDetailsDto(app) : null;

        dto.recentOrders = orders.stream()
                .map(this::toOrderRefDto)
                .toList();

        return dto;
    }

    /**
     * Maps an order entity to an order reference DTO.
     * Pure — no database access.
     */
    public AdminOrderRefDto toOrderRefDto(OrderEntity o) {
        AdminOrderRefDto dto = new AdminOrderRefDto();
        dto.id        = o.id.toString();
        dto.reference = "ORD-" + o.id.toString().substring(0, 8).toUpperCase();
        dto.placedAt  = o.createdAt != null ? o.createdAt.toString() : null;
        dto.total     = o.totalAmount != null ? o.totalAmount.doubleValue() : 0.0;
        dto.status    = o.status != null ? o.status.name() : null;
        return dto;
    }
}
