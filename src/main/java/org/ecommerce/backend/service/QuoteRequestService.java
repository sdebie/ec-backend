package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.backend.mapper.QuoteRequestMapper;
import org.ecommerce.common.dto.QuoteItemPriceInput;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.ecommerce.common.dto.QuoteRequestLineDto;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class QuoteRequestService
{
    private static final Logger LOG = Logger.getLogger(QuoteRequestService.class);
    private static final int MAX_NOTES_LENGTH = 2000;

    @Inject
    Event<QuoteRequestSubmittedEvent> submittedEvent;

    @Inject
    Event<QuoteGeneratedEvent> quoteGeneratedEvent;

    @Inject
    QuoteRequestMapper quoteRequestMapper;

    /**
     * Submits a new quote request: resolves each variant (unknown → exception for 422),
     * snapshots product name + variant SKU, persists the aggregate, and fires a CDI event
     * observed post-commit by the mailer.
     */
    @Transactional
    public QuoteRequestEntity submit(QuoteRequestSubmissionDto dto)
    {
        QuoteRequestEntity request = new QuoteRequestEntity();
        request.setName(dto.name());
        request.setEmail(dto.email());
        request.setPhone(dto.phone());
        request.setCompany(dto.company());
        request.setMessage(dto.message());
        request.setStatus(QuoteRequestStatusEn.NEW);
        request.setCreatedAt(Instant.now());

        List<QuoteRequestItemEntity> items = new ArrayList<>();
        for (QuoteRequestLineDto line : dto.items()) {
            ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(line.variantId());
            if (variant == null) {
                throw new IllegalArgumentException("Unknown variant: " + line.variantId());
            }

            QuoteRequestItemEntity item = new QuoteRequestItemEntity();
            item.setQuoteRequest(request);
            item.setVariant(variant);
            item.setProductNameSnapshot(variant.getProduct().getName());
            item.setVariantSkuSnapshot(variant.getSku());
            item.setQuantity(line.quantity());
            items.add(item);
        }

        request.setItems(items);
        QuoteRequestEntity.persist(request);

        LOG.infof("[QuoteRequest] submitted id=%s, items=%d", request.getId(), items.size());

        // Fire post-commit CDI event — observed by QuoteRequestMailer at AFTER_SUCCESS
        submittedEvent.fire(new QuoteRequestSubmittedEvent(request.getId(), quoteRequestMapper.mapEntityToDetailsDto(request)));

        return request;
    }

    /**
     * Updates the status of a quote request using a forward-only transition map.
     * QUOTE_SENT is rejected here even when the graph would otherwise allow it — it is only
     * ever reachable through {@link #generateAndSendQuote}, since without pricing data it
     * would be a QUOTE_SENT request with no quote.
     * <p>
     * Maps to the DTO here, before returning, rather than leaving that to the caller: the
     * mapper reads the lazy {@code items} collection, and this method's own
     * {@code @Transactional} scope is the only guaranteed-open Hibernate session in the call
     * chain — a caller that maps afterward gets a detached entity and a
     * LazyInitializationException instead.
     */
    @Transactional
    public QuoteRequestDetailsDto updateStatus(UUID id, QuoteRequestStatusEn newStatus)
    {
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus is required");
        }
        if (newStatus == QuoteRequestStatusEn.QUOTE_SENT) {
            throw new IllegalArgumentException("Use generateAndSendQuote to move a request to QUOTE_SENT");
        }

        QuoteRequestEntity request = requireExisting(id);
        validateTransition(request.getStatus(), newStatus);

        QuoteRequestStatusEn previousStatus = request.getStatus();
        request.setStatus(newStatus);
        request.setStatusChangedAt(Instant.now());

        LOG.infof("[QuoteRequest] status updated id=%s, %s → %s", id, previousStatus, newStatus);

        return quoteRequestMapper.mapEntityToDetailsDto(request);
    }

    /**
     * Prices every requested item, computes the total, persists the quote, and moves the
     * request to QUOTE_SENT — the only path that may reach that status. Every item must be
     * priced; a partial itemPrices list is rejected rather than silently quoting a partial
     * total. Fires a post-commit event observed by {@link QuoteRequestMailer}.
     */
    @Transactional
    public QuoteRequestDetailsDto generateAndSendQuote(UUID id, List<QuoteItemPriceInput> itemPrices, String notes, StaffUserEntity quotedBy)
    {
        if (quotedBy == null) {
            throw new IllegalArgumentException("quotedBy is required");
        }
        validateNotes(notes);

        QuoteRequestEntity request = requireExisting(id);
        validateTransition(request.getStatus(), QuoteRequestStatusEn.QUOTE_SENT);

        Map<UUID, BigDecimal> priceByItemId = resolvePriceMap(request, itemPrices);
        BigDecimal total = BigDecimal.ZERO;
        for (QuoteRequestItemEntity item : request.getItems()) {
            BigDecimal price = priceByItemId.get(item.getId());
            item.setUnitPrice(price);
            total = total.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        request.setQuotedAmount(total);
        request.setQuotedNotes(notes);
        request.setQuotedBy(quotedBy);
        request.setStatus(QuoteRequestStatusEn.QUOTE_SENT);
        request.setStatusChangedAt(Instant.now());

        LOG.infof("[QuoteRequest] quote generated id=%s, total=%s, quotedBy=%s", id, total, quotedBy.getEmail());

        QuoteRequestDetailsDto dto = quoteRequestMapper.mapEntityToDetailsDto(request);
        quoteGeneratedEvent.fire(new QuoteGeneratedEvent(id, dto));
        return dto;
    }

    /**
     * Renders what {@link #generateAndSendQuote} would produce, without persisting anything.
     * Runs the identical price-coverage validation, so a preview that succeeds guarantees the
     * real send will too. Only the returned DTO carries the staff-entered prices — the
     * managed entity is never mutated, so there is nothing for this transaction to flush.
     */
    @Transactional
    public QuoteRequestDetailsDto previewQuote(UUID id, List<QuoteItemPriceInput> itemPrices, String notes, StaffUserEntity quotedBy)
    {
        if (quotedBy == null) {
            throw new IllegalArgumentException("quotedBy is required");
        }
        validateNotes(notes);

        QuoteRequestEntity request = requireExisting(id);
        Map<UUID, BigDecimal> priceByItemId = resolvePriceMap(request, itemPrices);
        QuoteRequestDetailsDto dto = quoteRequestMapper.mapEntityToDetailsDto(request);

        BigDecimal total = BigDecimal.ZERO;
        for (QuoteRequestItemDto item : dto.getItems()) {
            BigDecimal price = priceByItemId.get(item.getId());
            item.setUnitPrice(price);
            item.setLineTotal(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            total = total.add(item.getLineTotal());
        }

        dto.setQuotedAmount(total);
        dto.setQuotedNotes(notes);
        dto.setQuotedByName(quotedBy.getFullName());
        return dto;
    }

    private QuoteRequestEntity requireExisting(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        QuoteRequestEntity request = QuoteRequestEntity.findById(id);
        if (request == null) {
            throw new IllegalArgumentException("Quote request not found: " + id);
        }
        return request;
    }

    private void validateNotes(String notes)
    {
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            throw new IllegalArgumentException("notes must be " + MAX_NOTES_LENGTH + " characters or fewer");
        }
    }

    /** Validates that itemPrices covers exactly the request's own items — no fewer, no unknown ids. */
    private Map<UUID, BigDecimal> resolvePriceMap(QuoteRequestEntity request, List<QuoteItemPriceInput> itemPrices)
    {
        if (itemPrices == null || itemPrices.isEmpty()) {
            throw new IllegalArgumentException("At least one item price is required");
        }

        Map<UUID, BigDecimal> priceByItemId = itemPrices.stream()
                .collect(Collectors.toMap(QuoteItemPriceInput::itemId, QuoteItemPriceInput::unitPrice));

        Set<UUID> requestItemIds = request.getItems().stream()
                .map(QuoteRequestItemEntity::getId)
                .collect(Collectors.toSet());

        if (!priceByItemId.keySet().equals(requestItemIds)) {
            throw new IllegalArgumentException("Item prices must cover exactly the request's own items");
        }

        return priceByItemId;
    }

    /**
     * Validates that the transition from current to target is allowed:
     * NEW→IN_PROGRESS, NEW→CLOSED, NEW→QUOTE_SENT, IN_PROGRESS→CLOSED, IN_PROGRESS→QUOTE_SENT,
     * QUOTE_SENT→CLOSED. CLOSED is terminal; QUOTE_SENT's only forward move is to CLOSED.
     */
    private void validateTransition(QuoteRequestStatusEn current, QuoteRequestStatusEn target)
    {
        boolean valid = switch (current) {
            case NEW -> target == QuoteRequestStatusEn.IN_PROGRESS
                    || target == QuoteRequestStatusEn.CLOSED
                    || target == QuoteRequestStatusEn.QUOTE_SENT;
            case IN_PROGRESS -> target == QuoteRequestStatusEn.CLOSED
                    || target == QuoteRequestStatusEn.QUOTE_SENT;
            case QUOTE_SENT -> target == QuoteRequestStatusEn.CLOSED;
            case CLOSED -> false;
        };

        if (!valid) {
            throw new InvalidQuoteStatusTransitionException(current, target);
        }
    }

}
