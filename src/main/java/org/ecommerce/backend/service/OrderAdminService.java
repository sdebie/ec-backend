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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.ecommerce.common.enums.FulfilmentState;
import org.ecommerce.common.enums.PaymentState;
import java.util.function.Function;
import java.util.EnumSet;
import java.util.Set;
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
     * One order carries one status, but staff filter on two separate questions of it — has
     * the money arrived, and where are the goods. Each facet resolves back to the statuses
     * that report it, and supplying both intersects the two sets.
     *
     * @param paymentState    a {@link PaymentState} name, or null/"ALL" for any
     * @param fulfilmentState a {@link FulfilmentState} name, or null/"ALL" for any
     * @param fromDate        inclusive ISO date (yyyy-MM-dd), or null
     * @param toDate          inclusive ISO date (yyyy-MM-dd), or null
     */
    public PageResponse<AdminOrderListItemDto> adminOrderList(int pageIndex, int pageSize,
                                                              String paymentState, String fulfilmentState,
                                                              String fromDate, String toDate)
    {
        Set<OrderStatusEn> statusFilter = resolveStatuses(paymentState, fulfilmentState);

        LocalDate fromDay = parseDate(fromDate, "fromDate");
        LocalDateTime from = fromDay == null ? null : fromDay.atStartOfDay();

        // The UI sends whole days, so an inclusive "to" is every instant before
        // the following midnight — a plain <= would drop everything after 00:00.
        LocalDate toDay = parseDate(toDate, "toDate");
        LocalDateTime toExclusive = toDay == null ? null : toDay.plusDays(1).atStartOfDay();

        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageIndex(pageIndex);
        pageRequest.setPageSize(pageSize);

        // An empty set means the two facets admit nothing between them, which is a valid
        // question with no answers — not a query. `status in ()` is not valid SQL.
        if (statusFilter != null && statusFilter.isEmpty()) {
            return new PageResponse<>(List.of(), 0, 0, pageRequest.getPageIndex(), pageRequest.getPageSize());
        }

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
        OrderTotals totals = orderService.computeTotals(orderService.subtotalOf(order), order.getShippingMethod());

        List<OrderStatusHistoryEntity> history = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", id)
                .list();

        return orderAdminMapper.toDetailDto(order, totals, history);
    }

    /**
     * The statuses both facets admit, or null when neither is set and every status matches.
     * Returns an empty set when the two do not overlap — a filter pair with no answers.
     */
    private Set<OrderStatusEn> resolveStatuses(String paymentState, String fulfilmentState)
    {
        Set<OrderStatusEn> byPayment = parseFacet(paymentState, PaymentState.class, PaymentState::statusesFor);
        Set<OrderStatusEn> byFulfilment = parseFacet(fulfilmentState, FulfilmentState.class, FulfilmentState::statusesFor);

        if (byPayment == null) {
            return byFulfilment;
        }
        if (byFulfilment == null) {
            return byPayment;
        }

        Set<OrderStatusEn> both = EnumSet.copyOf(byPayment);
        both.retainAll(byFulfilment);
        return both;
    }

    private <E extends Enum<E>> Set<OrderStatusEn> parseFacet(String value, Class<E> type,
                                                              Function<E, Set<OrderStatusEn>> statusesFor)
    {
        if (value == null || value.isBlank() || ALL.equals(value)) {
            return null;
        }
        try {
            return statusesFor.apply(Enum.valueOf(type, value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + type.getSimpleName() + ": " + value);
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
