package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.exception.InvalidQuoteStatusTransitionException;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
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
public class QuoteRequestService {

    private static final Logger LOG = Logger.getLogger(QuoteRequestService.class);

    @Inject
    Event<QuoteRequestSubmittedEvent> submittedEvent;

    /**
     * Submits a new quote request: resolves each variant (unknown → exception for 422),
     * snapshots product name + variant SKU, persists the aggregate, and fires a CDI event
     * observed post-commit by the mailer.
     */
    @Transactional
    public QuoteRequestEntity submit(QuoteRequestSubmissionDto dto) {
        QuoteRequestEntity request = new QuoteRequestEntity();
        request.name = dto.name();
        request.email = dto.email();
        request.phone = dto.phone();
        request.company = dto.company();
        request.message = dto.message();
        request.status = QuoteRequestStatusEn.NEW;
        request.createdAt = Instant.now();

        List<QuoteRequestItemEntity> items = new ArrayList<>();
        for (QuoteRequestLineDto line : dto.items()) {
            ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(line.variantId());
            if (variant == null) {
                throw new IllegalArgumentException("Unknown variant: " + line.variantId());
            }

            QuoteRequestItemEntity item = new QuoteRequestItemEntity();
            item.quoteRequest = request;
            item.variant = variant;
            item.productNameSnapshot = variant.product.name;
            item.variantSkuSnapshot = variant.sku;
            item.quantity = line.quantity();
            items.add(item);
        }

        request.items = items;
        QuoteRequestEntity.persist(request);

        LOG.infof("[QuoteRequest] submitted id=%s, items=%d", request.id, items.size());

        // Fire post-commit CDI event — observed by QuoteRequestMailer at AFTER_SUCCESS
        submittedEvent.fire(new QuoteRequestSubmittedEvent(request.id, toDetailsDto(request)));

        return request;
    }

    /**
     * Updates the status of a quote request using a forward-only transition map:
     * NEW→IN_PROGRESS, NEW→CLOSED, IN_PROGRESS→CLOSED. All others throw.
     */
    @Transactional
    public QuoteRequestEntity updateStatus(UUID id, QuoteRequestStatusEn newStatus) {
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

        validateTransition(request.status, newStatus);

        QuoteRequestStatusEn previousStatus = request.status;
        request.status = newStatus;
        request.statusChangedAt = Instant.now();

        LOG.infof("[QuoteRequest] status updated id=%s, %s → %s", id, previousStatus, newStatus);

        return request;
    }

    /**
     * Validates that the transition from current to target is allowed.
     * Valid transitions: NEW→IN_PROGRESS, NEW→CLOSED, IN_PROGRESS→CLOSED.
     */
    private void validateTransition(QuoteRequestStatusEn current, QuoteRequestStatusEn target) {
        boolean valid = switch (current) {
            case NEW -> target == QuoteRequestStatusEn.IN_PROGRESS || target == QuoteRequestStatusEn.CLOSED;
            case IN_PROGRESS -> target == QuoteRequestStatusEn.CLOSED;
            case CLOSED -> false;
        };

        if (!valid) {
            throw new InvalidQuoteStatusTransitionException(current, target);
        }
    }

    private QuoteRequestDetailsDto toDetailsDto(QuoteRequestEntity entity) {
        QuoteRequestDetailsDto dto = new QuoteRequestDetailsDto();
        dto.setId(entity.id);
        dto.setName(entity.name);
        dto.setEmail(entity.email);
        dto.setPhone(entity.phone);
        dto.setCompany(entity.company);
        dto.setMessage(entity.message);
        dto.setCreatedAt(entity.createdAt);
        dto.setStatus(entity.status);
        dto.setStatusChangedAt(entity.statusChangedAt);

        List<QuoteRequestItemDto> itemDtos = new ArrayList<>();
        for (QuoteRequestItemEntity item : entity.items) {
            QuoteRequestItemDto itemDto = new QuoteRequestItemDto();
            itemDto.setVariantId(item.variant != null ? item.variant.id : null);
            itemDto.setProductNameSnapshot(item.productNameSnapshot);
            itemDto.setVariantSkuSnapshot(item.variantSkuSnapshot);
            itemDto.setQuantity(item.quantity);
            itemDtos.add(itemDto);
        }
        dto.setItems(itemDtos);

        return dto;
    }
}
