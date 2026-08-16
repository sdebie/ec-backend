package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.backend.mapper.QuoteRequestMapper;
import org.ecommerce.common.dto.QuoteRequestLineDto;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class QuoteRequestService
{
    private static final Logger LOG = Logger.getLogger(QuoteRequestService.class);

    @Inject
    Event<QuoteRequestSubmittedEvent> submittedEvent;

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
     * Updates the status of a quote request using a forward-only transition map:
     * NEW→IN_PROGRESS, NEW→CLOSED, IN_PROGRESS→CLOSED. All others throw.
     */
    @Transactional
    public QuoteRequestEntity updateStatus(UUID id, QuoteRequestStatusEn newStatus)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus is required");
        }

        QuoteRequestEntity request = QuoteRequestEntity.findById(id);
        if (request == null) {
            throw new IllegalArgumentException("Quote request not found: " + id);
        }

        validateTransition(request.getStatus(), newStatus);

        QuoteRequestStatusEn previousStatus = request.getStatus();
        request.setStatus(newStatus);
        request.setStatusChangedAt(Instant.now());

        LOG.infof("[QuoteRequest] status updated id=%s, %s → %s", id, previousStatus, newStatus);

        return request;
    }

    /**
     * Validates that the transition from current to target is allowed.
     * Valid transitions: NEW→IN_PROGRESS, NEW→CLOSED, IN_PROGRESS→CLOSED.
     */
    private void validateTransition(QuoteRequestStatusEn current, QuoteRequestStatusEn target)
    {
        boolean valid = switch (current) {
            case NEW -> target == QuoteRequestStatusEn.IN_PROGRESS || target == QuoteRequestStatusEn.CLOSED;
            case IN_PROGRESS -> target == QuoteRequestStatusEn.CLOSED;
            case CLOSED -> false;
        };

        if (!valid) {
            throw new InvalidQuoteStatusTransitionException(current, target);
        }
    }

}
