package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.QuoteRequestService;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.ecommerce.common.dto.QuoteRequestListItemDto;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.QuoteRequestRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GraphQLApi
public class QuoteRequestAdminResource {

    @Inject
    QuoteRequestService quoteRequestService;

    @Inject
    QuoteRequestRepository quoteRequestRepository;

    @Query("allQuoteRequests")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public List<QuoteRequestListItemDto> allQuoteRequests(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest
    ) {
        try {
            if (pageRequest == null) {
                pageRequest = new PageRequest();
            }
            return quoteRequestRepository.findAll(pageRequest, filterRequest).stream()
                    .map(this::toListItemDto)
                    .toList();
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("quoteRequestCount")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public long quoteRequestCount(@Name("filterRequest") FilterRequest filterRequest) {
        try {
            return quoteRequestRepository.count(filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("quoteRequest")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public QuoteRequestDetailsDto quoteRequest(@Name("id") UUID id) {
        try {
            if (id == null) {
                throw new IllegalArgumentException("id is required");
            }
            QuoteRequestEntity entity = quoteRequestRepository.findById(id);
            if (entity == null) {
                throw new IllegalArgumentException("Quote request not found: " + id);
            }
            return toDetailsDto(entity);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("updateQuoteRequestStatus")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public QuoteRequestDetailsDto updateQuoteRequestStatus(@Name("id") UUID id, @Name("status") String status) {
        try {
            QuoteRequestStatusEn statusEnum = parseStatus(status);
            QuoteRequestEntity updated = quoteRequestService.updateStatus(id, statusEnum);
            return toDetailsDto(updated);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    private QuoteRequestStatusEn parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        try {
            return QuoteRequestStatusEn.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
    }

    private QuoteRequestListItemDto toListItemDto(QuoteRequestEntity entity) {
        QuoteRequestListItemDto dto = new QuoteRequestListItemDto();
        dto.setId(entity.id);
        dto.setName(entity.name);
        dto.setCompany(entity.company);
        dto.setItemCount(entity.items != null ? entity.items.size() : 0);
        dto.setCreatedAt(entity.createdAt);
        dto.setStatus(entity.status);
        return dto;
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
        if (entity.items != null) {
            for (QuoteRequestItemEntity item : entity.items) {
                QuoteRequestItemDto itemDto = new QuoteRequestItemDto();
                itemDto.setVariantId(item.variant != null ? item.variant.id : null);
                itemDto.setProductNameSnapshot(item.productNameSnapshot);
                itemDto.setVariantSkuSnapshot(item.variantSkuSnapshot);
                itemDto.setQuantity(item.quantity);
                itemDtos.add(itemDto);
            }
        }
        dto.setItems(itemDtos);

        return dto;
    }

    private RuntimeException toGraphQlException(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = "quote request operation failed";
        }
        return new IllegalArgumentException(message, ex);
    }
}
