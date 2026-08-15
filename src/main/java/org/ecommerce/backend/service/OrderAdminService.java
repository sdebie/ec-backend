package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.OrderAdminMapper;
import org.ecommerce.common.dto.AdminOrderDetailDto;
import org.ecommerce.common.dto.AdminOrderListItemDto;
import org.ecommerce.common.dto.PageResponse;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * The staff view of orders — the list staff work from and the detail they
 * fulfil against. Kept apart from {@link OrderService}, which owns the
 * shopper-facing lifecycle, the same way {@code CustomerAdminService} is kept
 * apart from customer registration.
 */
@ApplicationScoped
public class OrderAdminService
{
    /** The UI's "no filter" sentinel; it must never reach a query. */
    private static final String ALL = "ALL";

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderAdminMapper orderAdminMapper;

    @Inject
    OrderService orderService;

    /**
     * @param status   an {@link OrderStatusEn} name, or null/"ALL" for every status
     * @param fromDate inclusive ISO date (yyyy-MM-dd), or null
     * @param toDate   inclusive ISO date (yyyy-MM-dd), or null
     */
    public PageResponse<AdminOrderListItemDto> adminOrderList(int pageIndex, int pageSize, String status, String fromDate, String toDate)
    {
        OrderStatusEn statusFilter = parseStatus(status);

        LocalDate fromDay = parseDate(fromDate, "fromDate");
        LocalDateTime from = fromDay == null ? null : fromDay.atStartOfDay();

        // The UI sends whole days, so an inclusive "to" is every instant before
        // the following midnight — a plain <= would drop everything after 00:00.
        LocalDate toDay = parseDate(toDate, "toDate");
        LocalDateTime toExclusive = toDay == null ? null : toDay.plusDays(1).atStartOfDay();

        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageIndex(pageIndex);
        pageRequest.setPageSize(pageSize);

        List<AdminOrderListItemDto> content = orderRepository
                .findForAdmin(statusFilter, from, toExclusive, pageRequest)
                .stream()
                .map(orderAdminMapper::toListItemDto)
                .toList();

        long totalElements = orderRepository.countForAdmin(statusFilter, from, toExclusive);
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.getPageSize());

        return new PageResponse<>(content, totalElements, totalPages, pageRequest.getPageIndex(), pageRequest.getPageSize());
    }

    public AdminOrderDetailDto adminOrder(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        OrderEntity order = orderRepository.findOrderInfoById(id);
        if (order == null) {
            return null;
        }

        // The money breakdown comes from the same computeTotals path that priced
        // the order, so the components staff see reconcile with what was charged.
        BigDecimal subtotal = orderService.subtotalOf(order);
        OrderTotals totals = orderService.computeTotals(subtotal, order.getShippingMethod());

        List<OrderStatusHistoryEntity> history = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", id)
                .list();

        return orderAdminMapper.toDetailDto(order, subtotal, totals.shippingEstimate(), totals.vatAmount(), history);
    }

    private OrderStatusEn parseStatus(String status)
    {
        if (status == null || status.isBlank() || ALL.equals(status)) {
            return null;
        }
        try {
            return OrderStatusEn.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid status: " + status);
        }
    }

    private LocalDate parseDate(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + value + " (expected yyyy-MM-dd)");
        }
    }
}
