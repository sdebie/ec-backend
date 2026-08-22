package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.OrderAdminService;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.PageResponse;

import java.util.UUID;

/**
 * The staff order surface. Separate from {@link OrderResource}, which serves
 * shoppers their own orders, so the two audiences' role gates and shapes stay
 * independent.
 */
@GraphQLApi
public class OrderAdminResource
{
    @Inject
    OrderAdminService orderAdminService;

    @Query("adminOrderList")
    @Description("Admin paginated order list with customer, item count and total. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    @Transactional(TxType.SUPPORTS)
    public PageResponse<AdminOrderListItemDto> adminOrderList(
            @Name("pageIndex") @DefaultValue("0") int pageIndex,
            @Name("pageSize") @DefaultValue("10") int pageSize,
            @Name("paymentState") String paymentState,
            @Name("fulfilmentState") String fulfilmentState,
            @Name("fromDate") String fromDate,
            @Name("toDate") String toDate,
            @Name("sortBy") String sortBy,
            @Name("sortDir") String sortDir)
    {
        return orderAdminService.adminOrderList(
                pageIndex, pageSize, paymentState, fulfilmentState, fromDate, toDate, sortBy, sortDir);
    }

    @Query("adminOrder")
    @Description("Admin order detail: lines, address, money breakdown, status timeline and latest payment. Staff JWT required.")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    @Transactional(TxType.SUPPORTS)
    public AdminOrderDetailDto adminOrder(@Name("id") String id) throws GraphQLException
    {
        UUID orderId;
        try {
            orderId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Order not found");
        }

        AdminOrderDetailDto detail = orderAdminService.adminOrder(orderId);
        if (detail == null) {
            throw new GraphQLException("Order not found");
        }
        return detail;
    }
}
