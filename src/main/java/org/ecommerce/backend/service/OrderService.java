package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.backend.exception.IdempotencyConflictException;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.backend.mapper.OrderMapper;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.StockEffect;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.common.repository.OrderStatusHistoryRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
    OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    OrderMapper orderMapper;

    @Inject
    PricingService pricingService;

    @Inject
    TaxService taxService;

    @Inject
    ShippingService shippingService;

    @Inject
    OrderCapabilityService orderCapability;

    @ConfigProperty(name = "order.idempotency.replay-hours", defaultValue = "24")
    int replayWindowHours;

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    /**
     * Recorded on the status timeline for a transition no staff member made.
     */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    /**
     * The four refusal codes an idempotency-key request can receive in a
     * {@code 409} body's {@code code} field — the only field a caller may
     * branch on; {@code error} is a human message and may change wording
     * freely (design §6). Defined once here, read by the endpoint, the
     * backend tests and the storefront client.
     */
    public static final String CODE_IDEMPOTENCY_KEY_EXPIRED = "IDEMPOTENCY_KEY_EXPIRED";
    public static final String CODE_IDEMPOTENCY_ORDER_VOIDED = "IDEMPOTENCY_ORDER_VOIDED";
    public static final String CODE_IDEMPOTENCY_CART_MISMATCH = "IDEMPOTENCY_CART_MISMATCH";
    public static final String CODE_IDEMPOTENCY_WRONG_OWNER = "IDEMPOTENCY_WRONG_OWNER";

    /**
     * Most distinct lines one cart may check out with.
     * <p>
     * Checkout is unauthenticated by design, and every line costs a variant lookup
     * inside a single transaction, so an unbounded list lets one anonymous request
     * decide how much database work the server does. This is a structural bound
     * rather than a tuning knob: it sits far above any real cart — a wholesale
     * order of 200 distinct SKUs is already extraordinary — so raising it should
     * mean revisiting the bound, not editing a config value.
     */
    public static final int MAX_ORDER_LINES = 200;

    @Transactional
    public OrderCheckoutResponseDto createOrderFromCart(OrderCreationRequestDto request, CustomerTypeEn customerTier,
                                                        CustomerEntity customer, UUID idempotencyKey, String cartFingerprint)
    {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one item");
        }

        // Bound the work before doing any of it: the checks below cost a query per
        // line, and this endpoint takes anonymous requests.
        if (request.getItems().size() > MAX_ORDER_LINES) {
            throw new IllegalArgumentException("An order may not contain more than " + MAX_ORDER_LINES + " lines");
        }

        List<String> unavailableVariantIds = new ArrayList<>();
        List<OrderCreationItemDto> validItems = new ArrayList<>();
        List<ProductVariantEntity> validVariants = new ArrayList<>();

        // 1. Validate each item: existence, active status, and stock
        for (OrderCreationItemDto item : request.getItems()) {
            // A line naming no variant is a malformed request, not a variant that
            // cannot be bought, so it is refused rather than reported as unavailable:
            // the caller has nothing to act on. Refusing the whole cart is the point —
            // skipping the line would hand back an order shorter than the one
            // submitted, priced accordingly, with nothing to say a line went missing.
            if (item == null || item.getVariantId() == null || item.getVariantId().isBlank()) {
                throw new IllegalArgumentException("Every order line must name a variant");
            }
            UUID variantUuid;
            try {
                variantUuid = UUID.fromString(item.getVariantId());
            } catch (IllegalArgumentException e) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            ProductVariantEntity variant = productVariantRepository.findByIdWithProduct(variantUuid);
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

        // Nothing above can reach this with an empty list — every line either lands in
        // validItems or refuses the request. It is asserted anyway because the failure
        // it guards is a quite one: an order with no lines still carries the delivery
        // estimate as its total, so it is payable, it reaches the payment gateway, and
        // it appears to staff as an ordinary order. Any future path that drops a line
        // must fail here rather than persist one.
        if (validItems.isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one item");
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
        order.setVatAmount(vatAmount);
        order.setShippingCost(shippingEstimate);
        order.setStatus(OrderStatusEn.CREATED);
        order.setIdempotencyKey(idempotencyKey);
        order.setCartFingerprint(cartFingerprint);

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

        // The claim is a race, not a check (design §3.2): insert optimistically
        // and let the unique index on idempotency_key decide who won. flush()
        // forces the constraint now, inside this method's own catch, rather than
        // at commit where it would surface as an opaque transaction failure.
        try {
            OrderEntity.persist(order);
            OrderEntity.flush();
        } catch (PersistenceException e) {
            if (!isUniqueViolationOf(e, "ux_orders_idempotency_key")) {
                throw e;
            }
            // Another delivery of this same intent won the race. Its order is
            // the real one; the caller re-resolves against it.
            throw new IdempotencyConflictException(idempotencyKey);
        }
        orderStatusHistoryRepository.record(order, OrderStatusEn.CREATED, "Order placed", SYSTEM_ACTOR);

        // 7. Build and return response
        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(order.getId().toString());
        response.setSessionId(sessionId.toString());
        response.setLines(lines);
        response.setSubtotal(subtotal);
        response.setVatAmount(vatAmount);
        response.setShippingEstimate(shippingEstimate);
        response.setGrandTotal(grandTotal);
        response.setOrderToken(orderCapability.mint(order.getId()));
        return response;
    }

    /**
     * Whether a persistence failure is a unique-constraint violation on the
     * named constraint — the signal that a concurrent delivery of the same
     * idempotency key won the race, not a genuine fault (design §3.2).
     * <p>
     * {@code em.flush()} wraps the real failure in
     * {@code jakarta.persistence.PersistenceException}; the underlying
     * {@code org.hibernate.exception.ConstraintViolationException} — never
     * {@code jakarta.validation.ConstraintViolationException}, a same-named
     * bean-validation class that is never thrown here — is found by
     * unwrapping the cause chain. Matched on constraint name so an unrelated
     * fault on {@code orders} is never mistaken for a lost race.
     */
    static boolean isUniqueViolationOf(Throwable e, String constraintName)
    {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                return constraintName.equals(cve.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * A stable identity for one checkout request's cart, used to detect an
     * {@code Idempotency-Key} reused against a changed cart (Requirement 3).
     * <p>
     * Computed from the submitted lines alone — before validation, without
     * touching the database — because the fast-path idempotency lookup needs it
     * on a cart that may never be validated at all. It is therefore total: a
     * line naming no variant or carrying a null quantity is hashed as given
     * rather than thrown on.
     * <p>
     * Aggregates quantity per variant id and sorts by variant id before hashing,
     * so the same cart submitted with its lines reordered, or split across two
     * lines for one variant, fingerprints identically. Hashes the normalised
     * cart, never the raw request body: two serialisations of an identical cart
     * are not guaranteed byte-identical (JSON key order, whitespace), so a
     * raw-body hash would turn the network retry this feature protects into a
     * hard checkout failure.
     */
    public static String fingerprint(List<OrderCreationItemDto> items)
    {
        Map<String, Integer> quantityByVariantId = new HashMap<>();
        if (items != null) {
            for (OrderCreationItemDto item : items) {
                if (item == null) {
                    continue;
                }
                String variantId = String.valueOf(item.getVariantId());
                int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
                quantityByVariantId.merge(variantId, quantity, Integer::sum);
            }
        }

        String normalised = quantityByVariantId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("|"));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
     * @throws IllegalArgumentException if the transition is not one this source may make.
     *                                  Whitelisted in {@code show-runtime-exception-message},
     *                                  so the message reaches the admin UI intact.
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

        orderStatusHistoryRepository.record(order, to,
                transitionComment(from, transition), transition.changedBy());

        // Last, and only once the claim is won: the shopper is never told about a
        // transition that lost a race. Which email — or none — is a property of the
        // destination status, so no writer can forget one or send the wrong one.
        orderNotificationService.sendStatusNotification(order, to);

        return TransitionOutcome.won(from, to);
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
     * <p>
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
     * selected delivery method and writes the new grand total back.
     * <p>
     * The order was priced at creation against the DEFAULT delivery estimate,
     * because no method has been chosen at that point. Once the shopper picks one
     * at checkout, the total must follow, otherwise the summary shows — and the
     * payment gateway charges — a figure that excludes the delivery they chose.
     * <p>
     * The subtotal is rebuilt from the stored line prices, which the server set
     * from the shopper's tier at creation. Nothing here re-prices a product.
     * <p>
     * Also overwrites the persisted {@code vatAmount}/{@code shippingCost} with this
     * reprice's figures — the admin detail breakdown reads those columns, so a stale
     * creation-time VAT or shipping figure would otherwise survive under a grand total
     * that has since moved on without it.
     * <p>
     * Caller must be inside a transaction: the entity is managed, so the new
     * total flushes on commit.
     */
    public OrderTotals repriceOrder(OrderEntity order)
    {
        OrderTotals totals = computeTotals(subtotalOf(order), order.getShippingMethod());
        order.setTotalAmount(totals.grandTotal());
        order.setVatAmount(totals.vatAmount());
        order.setShippingCost(totals.shippingEstimate());
        return totals;
    }

    /**
     * The money breakdown for the admin order-detail screen: what this order was
     * actually charged, not what it would cost today.
     * <p>
     * {@code vatAmount}/{@code shippingCost} come from the order's own persisted columns
     * whenever they exist — written once at creation and again at {@link #repriceOrder}
     * — so a later change to the VAT rate or a shipping method's fee can never move an
     * already-placed order's figures. Only an order placed before those columns existed
     * (null on both) falls back to a live {@link #computeTotals} estimate; for that order
     * alone the breakdown is a best-effort reconstruction against current settings; there
     * is no historical record to recover it from.
     */
    public OrderTotals totalsForAdminDetail(OrderEntity order)
    {
        BigDecimal subtotal = subtotalOf(order);
        if (order.getVatAmount() != null && order.getShippingCost() != null) {
            return new OrderTotals(subtotal, order.getVatAmount(), order.getShippingCost(), order.getTotalAmount());
        }

        OrderTotals estimate = computeTotals(subtotal, order.getShippingMethod());
        return new OrderTotals(subtotal, estimate.vatAmount(), estimate.shippingEstimate(), order.getTotalAmount());
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

    /**
     * Rebuilds a checkout response from an already-persisted order, for a
     * request replaying an {@code Idempotency-Key} (design §4).
     * <p>
     * <b>A read.</b> Must never call {@link #reserveStock}, must never call
     * {@link OrderStatusHistoryEntity#record}, must never notify, and must
     * never write {@code totalAmount} — which is exactly why this recomputes
     * totals via {@link #computeTotals} rather than calling
     * {@link #repriceOrder}: the latter is those same two lines plus a write.
     * Lives beside {@link #createOrderFromCart}, never inside it, so no
     * shared path can accidentally acquire a side effect.
     * <p>
     * The response can legitimately differ from the original if the shopper
     * has since chosen a delivery method — {@code shippingEstimate} and
     * {@code grandTotal} follow the order's current state, deliberately not
     * pinned by Requirement 1.5, which equates only the priced facts
     * (orderId, sessionId, lines' variantId/quantity/unitPrice/lineTotal,
     * subtotal) that cannot drift.
     * <p>
     * {@code orderToken} is minted fresh on every call (guest-order-authorization
     * Requirement 3.2) — there is no stored token to return (design.md §2), so a
     * replay at hour 20 of the 24-hour idempotency window still returns a token
     * usable for a full 60 minutes from that moment, never one already expired.
     */
    public OrderCheckoutResponseDto replayOrder(OrderEntity order)
    {
        BigDecimal subtotal = subtotalOf(order);
        OrderTotals totals = computeTotals(subtotal, order.getShippingMethod());

        List<OrderCheckoutLineDto> lines = new ArrayList<>();
        for (OrderItemEntity item : order.getItems()) {
            OrderCheckoutLineDto line = new OrderCheckoutLineDto();
            line.setVariantId(item.getVariant() != null ? item.getVariant().getId().toString() : null);
            line.setName(item.getVariant() != null && item.getVariant().getProduct() != null
                    ? item.getVariant().getProduct().getName() : null);
            line.setUnitPrice(item.getUnitPrice());
            line.setQuantity(item.getQuantity());
            line.setLineTotal(item.getUnitPrice() != null && item.getQuantity() != null
                    ? item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())) : null);
            lines.add(line);
        }

        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(order.getId().toString());
        response.setSessionId(order.getSessionId() != null ? order.getSessionId().toString() : null);
        response.setLines(lines);
        response.setSubtotal(subtotal);
        response.setVatAmount(totals.vatAmount());
        response.setShippingEstimate(totals.shippingEstimate());
        response.setGrandTotal(totals.grandTotal());
        response.setOrderToken(orderCapability.mint(order.getId()));
        return response;
    }

    /**
     * Whether a matched order is still within its replay window, measured
     * from {@code created_at} (Requirement 5). Compared in
     * {@code LocalDateTime}, the basis the column was written with — do not
     * mix in {@code Instant}/{@code OffsetDateTime}.
     * <p>
     * A domain rule with a config value behind it, so it lives here rather
     * than inline in the resource.
     */
    public boolean isWithinReplayWindow(OrderEntity order)
    {
        if (order.getCreatedAt() == null) {
            return false;
        }
        return order.getCreatedAt().isAfter(LocalDateTime.now().minusHours(replayWindowHours));
    }

    /**
     * Most transitions carry no courier details; this is the same move without them.
     */
    @Transactional
    public OrderResponseDto updateOrderStatus(UUID orderId, String newStatus, String changedBy)
            throws GraphQLException
    {
        return updateOrderStatus(orderId, newStatus, changedBy, null);
    }

    @Transactional
    public OrderResponseDto updateOrderStatus(UUID orderId, String newStatus, String changedBy,
                                              OrderTracking tracking) throws GraphQLException
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

        // Written before the transition so the notification, which is sent from inside it,
        // carries the reference rather than an email that arrives without one.
        if (tracking != null && !tracking.isEmpty()) {
            if (targetStatus != OrderStatusEn.IN_TRANSIT) {
                throw new IllegalArgumentException(
                        "Tracking details belong to the move to IN_TRANSIT, not to " + targetStatus);
            }
            order.setTrackingNumber(tracking.number());
            order.setTrackingCarrier(tracking.carrier());
        }

        TransitionOutcome outcome = applyTransition(order,
                StatusTransition.staff(targetStatus, changedBy));
        if (!outcome.claimed()) {
            throw new GraphQLException("Order status changed concurrently; please refresh and try again");
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