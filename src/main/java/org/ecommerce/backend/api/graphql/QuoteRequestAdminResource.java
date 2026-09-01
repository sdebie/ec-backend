package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.mapper.QuoteRequestMapper;
import org.ecommerce.backend.service.QuoteRequestMailer;
import org.ecommerce.backend.service.QuoteRequestService;
import org.ecommerce.common.dto.QuoteItemPriceInput;
import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestListItemDto;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.QuoteRequestStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.QuoteRequestRepository;
import org.ecommerce.common.repository.StaffRepository;

import java.util.List;
import java.util.UUID;

@GraphQLApi
public class QuoteRequestAdminResource
{
    @Inject
    QuoteRequestService quoteRequestService;

    @Inject
    QuoteRequestMapper quoteRequestMapper;

    @Inject
    QuoteRequestRepository quoteRequestRepository;

    @Inject
    StaffRepository staffRepository;

    @Inject
    QuoteRequestMailer quoteRequestMailer;

    @Inject
    JsonWebToken jwt;

    @Query("allQuoteRequests")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public List<QuoteRequestListItemDto> allQuoteRequests(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        try {
            if (pageRequest == null) {
                pageRequest = new PageRequest();
            }
            return quoteRequestRepository.findAll(pageRequest, filterRequest).stream()
                    .map(quoteRequestMapper::mapEntityToListItemDto)
                    .toList();
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("quoteRequestCount")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public long quoteRequestCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        try {
            return quoteRequestRepository.count(filterRequest);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Query("quoteRequest")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER", "VIEWER"})
    public QuoteRequestDetailsDto quoteRequest(@Name("id") UUID id)
    {
        try {
            if (id == null) {
                throw new IllegalArgumentException("id is required");
            }
            QuoteRequestEntity entity = quoteRequestRepository.findById(id);
            if (entity == null) {
                throw new IllegalArgumentException("Quote request not found: " + id);
            }
            return quoteRequestMapper.mapEntityToDetailsDto(entity);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("updateQuoteRequestStatus")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public QuoteRequestDetailsDto updateQuoteRequestStatus(@Name("id") UUID id, @Name("status") String status)
    {
        try {
            QuoteRequestStatusEn statusEnum = parseStatus(status);
            return quoteRequestService.updateStatus(id, statusEnum);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("saveQuoteDraft")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public QuoteRequestDetailsDto saveQuoteDraft(@Name("id") UUID id, @Name("items") List<QuoteItemPriceInput> items, @Name("notes") String notes)
    {
        try {
            return quoteRequestService.saveQuoteDraft(id, items, notes, resolveStaffUser());
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    @Mutation("generateAndSendQuote")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public QuoteRequestDetailsDto generateAndSendQuote(@Name("id") UUID id, @Name("items") List<QuoteItemPriceInput> items, @Name("notes") String notes)
    {
        try {
            return quoteRequestService.generateAndSendQuote(id, items, notes, resolveStaffUser());
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    /**
     * Renders the exact HTML the quote email would contain, without sending it or persisting
     * anything — lets staff check a quote before committing to generateAndSendQuote. A read,
     * not a write: no side effects, so this is a @Query despite taking a complex input.
     */
    @Query("previewQuoteEmail")
    @RolesAllowed({"SUPER_ADMIN", "ORDER_MANAGER"})
    public String previewQuoteEmail(@Name("id") UUID id, @Name("items") List<QuoteItemPriceInput> items, @Name("notes") String notes)
    {
        try {
            QuoteRequestDetailsDto preview = quoteRequestService.previewQuote(id, items, notes, resolveStaffUser());
            return quoteRequestMailer.renderQuotePreview(preview);
        } catch (RuntimeException ex) {
            throw toGraphQlException(ex);
        }
    }

    /** Who to credit as having generated the quote. The staff JWT carries their email as the subject. */
    private StaffUserEntity resolveStaffUser()
    {
        StaffUserEntity staff = jwt == null ? null : staffRepository.findByEmail(jwt.getName());
        if (staff == null) {
            throw new IllegalArgumentException("Unable to resolve the staff account for this request");
        }
        return staff;
    }

    private QuoteRequestStatusEn parseStatus(String status)
    {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        try {
            return QuoteRequestStatusEn.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
    }



    private RuntimeException toGraphQlException(RuntimeException ex)
    {
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
