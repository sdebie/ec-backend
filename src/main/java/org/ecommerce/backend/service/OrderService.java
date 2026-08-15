package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.mapper.OrderMapper;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.OrderItemEntity;
import org.ecommerce.common.entity.OrderStatusHistoryEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.StockEffect;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class OrderService
{
    @Inject
    OrderNotificationService orderNotificationService;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderMapper orderMapper;

    @Inject
    PricingService pricingService;

    @Inject
    TaxService taxService;

    @Inject
    ShippingService shippingService;

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    /** Recorded on the status timeline for a transition no staff member made. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    @Transactional
    public OrderCheckoutResponseDto createOrderFromCart(OrderCreationRequestDto request, CustomerTypeEn customerTier, CustomerEntity customer)
    {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one item");
        }

        List<String> unavailableVariantIds = new ArrayList<>();
        List<OrderCreationItemDto> validItems = new ArrayList<>();
        List<ProductVariantEntity> validVariants = new ArrayList<>();

        // 1. Validate each item: existence, active status, and stock
        for (OrderCreationItemDto item : request.getItems()) {
            if (item == null || item.getVariantId() == null) {
                continue;
            }
            UUID variantUuid;
            try {
                variantUuid = UUID.fromString(item.getVariantId());
            } catch (IllegalArgumentException e) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(variantUuid);
            if (variant == null || variant.getStatus() != ProductStatusEn.ACTIVE) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            int requested = item.getQuantity() != null ? item.getQuantity() : 0;
            if (requested <= 0 || variant.getStockQuantity() == null || variant.getStockQuantity() < requested) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            validItems.add(item);
            validVariants.add(variant);
        }

        // 2. Bail out if any variants are unavailable
        if (!unavailableVariantIds.isEmpty()) {
            throw new UnavailableVariantsException(unavailableVariantIds);
        }

        // 2b. Reserve stock: aggregate requested quantity per variant (a cart can
        // carry more than one line for the same variant) and decrement atomically,
        // so concurrent checkouts can never oversell the same unit. A failure here
        // rolls back the whole @Transactional method, so any decrements already
        // applied to other variants in this same request are released too.
        reserveStock(validVariants, validItems);

        // 3. Build checkout lines from server-side pricing
        List<OrderCheckoutLineDto> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);

            BigDecimal unitPrice = pricingService.getActivePrice(variant.getId(), customerTier);
            int quantity = item.getQuantity();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

            OrderCheckoutLineDto line = new OrderCheckoutLineDto();
            line.setVariantId(variant.getId().toString());
            line.setName(variant.getProduct() != null ? variant.getProduct().getName() : null);
            line.setUnitPrice(unitPrice);
            line.setQuantity(quantity);
            line.setLineTotal(lineTotal);
            lines.add(line);

            subtotal = subtotal.add(lineTotal);
        }

        // 4 & 5. Calculate tax and shipping. No method has been chosen yet at
        // creation time, so this falls back to the default estimate; the order is
        // repriced by repriceOrder() once the shopper selects one at checkout.
        OrderTotals totals = computeTotals(subtotal, null);
        BigDecimal vatAmount = totals.vatAmount();
        BigDecimal shippingEstimate = totals.shippingEstimate();
        BigDecimal grandTotal = totals.grandTotal();

        // 6. Persist the order
        UUID sessionId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.setSessionId(sessionId);
        order.setCustomerEntity(customer);
        order.setTotalAmount(grandTotal);
        order.setStatus(OrderStatusEn.CREATED);

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);
            BigDecimal unitPrice = lines.get(i).getUnitPrice();

            OrderItemEntity orderItem = new OrderItemEntity();
            orderItem.setOrderEntity(order);
            orderItem.setVariant(variant);
            orderItem.setQuantity(item.getQuantity());
            orderItem.setUnitPrice(unitPrice);
            order.getItems().add(orderItem);
        }

        OrderEntity.persist(order);
        OrderStatusHistoryEntity.record(order, OrderStatusEn.CREATED, "Order placed", SYSTEM_ACTOR);

        // 7. Build and return response
        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(order.getId().toString());
        response.setSessionId(sessionId.toString());
        response.setLines(lines);
        response.setSubtotal(subtotal);
        response.setVatAmount(vatAmount);
        response.setShippingEstimate(shippingEstimate);
        response.setGrandTotal(grandTotal);
        return response;
    }

    /**
     * Decrements stock for every valid line, aggregated by variant so duplicate
     * lines for the same variant are checked against their combined quantity
     * rather than independently (independently, two lines of 3 each both pass a
     * "3 <= 5 in stock" check even though 6 together oversells by 1). Each
     * decrement is an atomic conditional UPDATE — it fails closed if a concurrent
     * order already consumed the stock — so this is safe under concurrent
     * checkouts with no application-level locking.
     */
    private void reserveStock(List<ProductVariantEntity> validVariants, List<OrderCreationItemDto> validItems)
    {
        Map<UUID, Integer> requestedByVariantId = new LinkedHashMap<>();
        for (int i = 0; i < validVariants.size(); i++) {
            requestedByVariantId.merge(validVariants.get(i).getId(), validItems.get(i).getQuantity(), Integer::sum);
        }

        List<String> unavailableVariantIds = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : requestedByVariantId.entrySet()) {
            long updated = ProductVariantEntity.update(
                    "stockQuantity = stockQuantity - ?1 where id = ?2 and stockQuantity >= ?1",
                    entry.getValue(), entry.getKey());
            if (updated == 0) {
                unavailableVariantIds.add(entry.getKey().toString());
            }
        }

        if (!unavailableVariantIds.isEmpty()) {
            throw new UnavailableVariantsException(unavailableVariantIds);
        }
    }

    /**
     * Moves one order from the status the caller read to a new one, and applies
     * everything that transition entails — the stock movement and the timeline
     * entry — as a single unit.
     * <p>
     * <b>This is the only way an order's status changes.</b> Every writer goes
     * through here: checkout's pay-at-collection confirmation, the PayFast ITN
     * handler, the abandoned-order sweep and the staff mutation. Before that they
     * each hardcoded their own from/to pair and decided about stock for themselves,
     * so the field was guarded atomically while its side effects were coordinated
     * only by convention — a new writer could claim a status correctly and still
     * strand an order's goods, with nothing to catch it.
     * <p>
     * The status the caller already read is the status claimed. The claim is an
     * atomic conditional UPDATE, so losing it means another writer moved the order
     * in between; that is reported as an outcome rather than thrown, because what
     * to do about it differs per caller.
     * <p>
     * Ordering matters: nothing is written until the claim is won. Since every
     * stock-returning status is terminal, and a conditional UPDATE admits exactly
     * one winner, the loser of a race touches no stock and the restore happens
     * exactly once — with no version column and no double-restore guard.
     * <p>
     * Caller must be in a transaction.
     *
     * @throws IllegalArgumentException if the transition is not one this source may
     *                                  make, or if the restock answer does not match
     *                                  what the target status needs. Whitelisted in
     *                                  {@code show-runtime-exception-message}, so the
     *                                  message reaches the admin UI intact.
     */
    public TransitionOutcome applyTransition(OrderEntity order, StatusTransition transition)
    {
        OrderStatusEn from = order.getStatus();
        OrderStatusEn to = transition.to();

        // Checked before legality, and reported rather than thrown. A system writer
        // names the step it acts on, so finding the order elsewhere means another
        // writer reached it first — the expected outcome of a race, not a bug. The
        // conditional UPDATE below still does the real work; this only keeps a lost
        // race from surfacing as an illegal-transition error.
        if (transition.expectedFrom() != null && from != transition.expectedFrom()) {
            LOG.debugf("Order %s is %s, not the expected %s; another writer moved it first",
                    order.getId(), from, transition.expectedFrom());
            return TransitionOutcome.lost(from, to);
        }

        boolean permitted = transition.source() == TransitionSource.STAFF
                ? from != null && from.canTransitionTo(to)
                : from != null && from.canSystemTransitionTo(to);
        if (!permitted) {
            throw new IllegalArgumentException("Cannot move an order from " + from + " to " + to);
        }

        long claimed = OrderEntity.update("status = ?1 where id = ?2 and status = ?3", to, order.getId(), from);
        if (claimed == 0) {
            LOG.debugf("Lost the status claim on order %s: it is no longer %s", order.getId(), from);
            return TransitionOutcome.lost(from, to);
        }
        order.setStatus(to);

        // The destination decides, on its own, with no input from the caller. A refund
        // moves no stock: whether the goods came back is a physical fact the server does
        // not have, and putting them back on sale is the returns feature.
        boolean stockReturned = to.stockEffect() == StockEffect.RESTORE;
        if (stockReturned) {
            restoreStock(order);
        }

        OrderStatusHistoryEntity.record(order, to,
                transitionComment(from, transition), transition.changedBy());

        return TransitionOutcome.won(from, to, stockReturned);
    }

    /**
     * The timeline entry for a transition: what a system writer supplied, or for a
     * staff move the transition itself. Stock movement is not spelled out — it follows
     * from the target status alone, so saying so would be noise on every row.
     */
    private String transitionComment(OrderStatusEn from, StatusTransition transition)
    {
        return transition.comment() != null
                ? transition.comment()
                : from + " → " + transition.to();
    }

    /**
     * Returns an order's stock to inventory — the inverse of {@link #reserveStock},
     * which consumes it the moment an order reaches CREATED, before payment. Every
     * path that ends an order before its goods are dispatched must give that stock
     * back, or it is held by an order that will never ship and is lost for good.
     * <p>
     * Private on purpose. The exactly-once guarantee rests entirely on the caller
     * having won an atomic status claim first, and that precondition used to be
     * enforced by a sentence in a comment on a public method — so any new caller
     * that skipped the claim would double-restore, inflating stock invisibly until
     * an oversell. {@link #applyTransition} is now the only thing that can reach
     * this, and it always claims first.
     */
    private void restoreStock(OrderEntity order)
    {
        if (order == null || order.getItems() == null) {
            return;
        }

        for (OrderItemEntity item : order.getItems()) {
            if (item.getVariant() == null || item.getQuantity() == null) {
                continue;
            }
            ProductVariantEntity.update("stockQuantity = stockQuantity + ?1 where id = ?2",
                    item.getQuantity(), item.getVariant().getId());
        }
    }

    /**
     * Assembles the money on an order. The ONLY place subtotal, VAT, delivery and
     * grand total are combined, so order creation and later repricing can never
     * build a total differently.
     *
     * A null method means "not chosen yet" and falls back to the default
     * estimate, preserving what creation did before a method exists.
     */
    public OrderTotals computeTotals(BigDecimal subtotal, ShippingMethodEntity shippingMethod)
    {
        BigDecimal base = subtotal != null ? subtotal : BigDecimal.ZERO;
        BigDecimal vatAmount = taxService.calculateVat(base);
        BigDecimal shippingEstimate = shippingMethod != null && shippingMethod.getBaseFee() != null
                ? shippingMethod.getBaseFee()
                : shippingService.estimateShipping();

        return new OrderTotals(base, vatAmount, shippingEstimate, base.add(vatAmount).add(shippingEstimate));
    }

    /**
     * Recomputes an order's totals from its own persisted lines and its currently
     * selected delivery method, and writes the new grand total back.
     *
     * The order was priced at creation against the DEFAULT delivery estimate,
     * because no method has been chosen at that point. Once the shopper picks one
     * at checkout the total must follow, otherwise the summary shows — and the
     * payment gateway charges — a figure that excludes the delivery they chose.
     *
     * The subtotal is rebuilt from the stored line prices, which the server set
     * from the shopper's tier at creation. Nothing here re-prices a product.
     *
     * Caller must be inside a transaction: the entity is managed, so the new
     * total flushes on commit.
     */
    public OrderTotals repriceOrder(OrderEntity order)
    {
        OrderTotals totals = computeTotals(subtotalOf(order), order.getShippingMethod());
        order.setTotalAmount(totals.grandTotal());
        return totals;
    }

    /**
     * Sums an order's own persisted line prices. The single definition of what
     * an order's subtotal is, shared by repricing and by the admin detail
     * breakdown so the two can never derive it differently.
     */
    public BigDecimal subtotalOf(OrderEntity order)
    {
        BigDecimal subtotal = BigDecimal.ZERO;
        if (order.getItems() == null) {
            return subtotal;
        }

        for (OrderItemEntity item : order.getItems()) {
            if (item.getUnitPrice() == null || item.getQuantity() == null) {
                continue;
            }
            subtotal = subtotal.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return subtotal;
    }

    public OrderResponseDto getOrderById(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }
        return orderMapper.toResponseDto(orderRepository.findOrderInfoById(orderId));
    }

    public OrderResponseDto getLatestOrderBySessionId(String sessionId)
    {
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        return orderMapper.toResponseDto(order);
    }

    private OrderEntity findLatestOrderEntityBySessionId(String sessionId)
    {
        try {
            UUID sid = UUID.fromString(sessionId);
            LOG.debugf("getLatestOrderBySessionId for sessionId=%s", sid);
            return orderRepository.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies a staff-driven status change to one order.
     * <p>
     * Owns only what is specific to a staff request: parsing what was asked for,
     * turning a lost claim into something the admin UI can act on, and the
     * confirmation email. The transition itself belongs to
     * {@link #applyTransition}, which every other writer shares.
     *
     * @param changedBy display name of the staff member making the change, recorded on the timeline
     * @throws IllegalArgumentException if the transition is not one the workflow allows — thrown by
     *                                  {@link #applyTransition} and whitelisted so its message
     *                                  reaches the admin UI
     */
    @Transactional
    public OrderResponseDto updateOrderStatus(UUID orderId, String newStatus, String changedBy) throws GraphQLException
    {
        if (orderId == null) {
            throw new GraphQLException("orderId is required");
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw new GraphQLException("status is required");
        }
        LOG.debugf("Updating order status for orderId=%s to status=%s", orderId, newStatus);
        OrderEntity order = orderRepository.findOrderInfoById(orderId);
        if (order == null) {
            throw new GraphQLException("Order not found");
        }
        OrderStatusEn targetStatus;
        try {
            targetStatus = OrderStatusEn.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw new GraphQLException("Invalid status: " + newStatus);
        }

        TransitionOutcome outcome = applyTransition(order,
                StatusTransition.staff(targetStatus, changedBy));
        if (!outcome.claimed()) {
            throw new GraphQLException("Order status changed concurrently; please refresh and try again");
        }

        // Staff marking an order payable at collection is a confirmation to the shopper:
        // nothing further happens online, so this is the point their order is settled.
        if (outcome.to() == OrderStatusEn.IN_STORE_PAYMENT) {
            orderNotificationService.sendConfirmationEmail(order);
        }
        return orderMapper.toResponseDto(order);
    }

    public List<OrderResponseDto> getAllOrders(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<OrderEntity> orderEntities = orderRepository.findAllOrderInfo(pageRequest, filterRequest);
        List<OrderResponseDto> orders = new ArrayList<>(orderEntities.size());
        for (OrderEntity orderEntity : orderEntities) {
            orders.add(orderMapper.toResponseDto(orderEntity));
        }
        return orders;
    }

    public OrderDetailRespDto getOrderDetail(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }

        OrderEntity order = orderRepository.findOrderInfoById(orderId);
        if (order == null) {
            return null;
        }

        // Loaded here rather than inside the mapper: mappers do not open queries.
        List<OrderStatusHistoryEntity> history = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", orderId)
                .list();

        return orderMapper.toDetailDto(order, history);
    }

    public List<OrderSummaryDto> getMyOrders(UUID customerId)
    {
        List<OrderEntity> orders = OrderEntity
                .find("customerEntity.id = ?1 order by createdAt desc", customerId)
                .list();

        return orders.stream()
                .map(orderMapper::toSummaryDto)
                .collect(Collectors.toList());
    }

}