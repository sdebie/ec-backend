package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.dto.AdminOrderRefDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps customers into the admin-facing shapes — the list staff browse and the detail they
 * open. The shopper's own view of themselves is {@code CustomerPortalService}'s job.
 * <p>
 * Pure — no database access. The wholesale application and recent orders are loaded by
 * {@code CustomerAdminService} and passed in as sources; the nested application shape is
 * delegated to {@link WholesaleMapper}.
 * <p>
 * These take multiple <em>source</em> parameters rather than {@code @Context} because each
 * one contributes mapped fields. That is safe here only because no {@code @Mapping}
 * expression dereferences a source — MapStruct ANDs the top-level null checks, so an
 * expression reading {@code customer.x} directly would bypass its per-source guard.
 */
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ERROR, uses = {WholesaleMapper.class, TimestampMapper.class},
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface CustomerAdminMapper
{
    // Both sources carry id/firstName/lastName/status, so every collision is qualified.
    @Mapping(target = "id", source = "customer.id")
    @Mapping(target = "firstName", source = "customer.firstName")
    @Mapping(target = "lastName", source = "customer.lastName")
    @Mapping(target = "status", source = "customer.status")
    @Mapping(target = "email", source = "customer.user.email")
    @Mapping(target = "registeredAt", source = "customer.user.createdAt")
    @Mapping(target = "wholesaleApplicationStatus", source = "application.status")
    AdminCustomerListItemDto toListItemDto(CustomerEntity customer, WholesaleApplicationEntity application);

    @Mapping(target = "id", source = "customer.id")
    @Mapping(target = "firstName", source = "customer.firstName")
    @Mapping(target = "lastName", source = "customer.lastName")
    @Mapping(target = "phone", source = "customer.phone")
    @Mapping(target = "status", source = "customer.status")
    @Mapping(target = "shopperType", source = "customer.shopperType")
    @Mapping(target = "email", source = "customer.user.email")
    @Mapping(target = "registeredAt", source = "customer.user.createdAt")
    @Mapping(target = "wholesaleApplication", source = "application")
    @Mapping(target = "recentOrders", source = "orders")
    AdminCustomerDetailDto toDetailDto(CustomerEntity customer,
                                       WholesaleApplicationEntity application,
                                       List<OrderEntity> orders);

    @Mapping(target = "total", source = "totalAmount")
    @Mapping(target = "placedAt", source = "createdAt")
    AdminOrderRefDto toOrderRefDto(OrderEntity order);

    List<AdminOrderRefDto> toOrderRefDtos(List<OrderEntity> orders);


}
