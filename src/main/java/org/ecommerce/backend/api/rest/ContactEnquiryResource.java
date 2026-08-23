package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.exception.RecipientNotConfiguredException;
import org.ecommerce.backend.service.ContactEnquiryMailer;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Public REST endpoint for storefront contact enquiries.
 * <p>
 * No auth annotation — public by design, matching {@code OrderResource}.
 * <p>
 * Flow: honeypot check → rate-limit check → resolve recipient → async send → 202.
 * <p>
 * Status codes:
 * <ul>
 *   <li>{@code 202} — accepted (delivery is async; also returned for honeypot traps)</li>
 *   <li>{@code 422} — validation failure (well-formed body, invalid content)</li>
 *   <li>{@code 429} — rate-limited</li>
 *   <li>{@code 503} — no recipient configured</li>
 * </ul>
 * Provider errors are never leaked to the client.
 * <p>
 * Validation is performed explicitly (rather than via {@code @Valid}) so this
 * endpoint can return {@code 422} per its contract without a global constraint-
 * violation mapper that would change the {@code 400} behaviour of every other
 * validated endpoint (e.g. {@code StaffResource}).
 */
@Path("/api/storefront/enquiries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContactEnquiryResource {

    private static final Logger LOG = Logger.getLogger(ContactEnquiryResource.class);

    @Inject
    ContactEnquiryMailer contactEnquiryMailer;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    Validator validator;

    @POST
    public Response submitEnquiry(
            ContactEnquiryRequestDto dto,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        // 1. Validation — 422 for a well-formed but invalid body (Req 2.5, 3.1)
        if (dto == null) {
            return Response.status(422).build();
        }
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            return Response.status(422).build();
        }

        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);

        // 2. Honeypot check — non-empty means bot; silent accept, no send
        if (dto.website() != null && !dto.website().isBlank()) {
            LOG.infof("[ContactEnquiry] honeypot triggered (IP: %s)", clientIp);
            return Response.status(Response.Status.ACCEPTED).build();
        }

        // 3. Rate-limit check
        Response limited = rateLimiterService.enforce("enquiry", clientIp, 5, 3600);
        if (limited != null) {
            return limited;
        }

        // 4. Resolve recipient + send (async fire-and-log)
        try {
            contactEnquiryMailer.send(dto);
        } catch (RecipientNotConfiguredException e) {
            LOG.errorf("[ContactEnquiry] no recipient configured: %s", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            // Never leak provider errors to the client
            LOG.errorf(e, "[ContactEnquiry] unexpected error processing enquiry from %s", dto.email());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }

        return Response.status(Response.Status.ACCEPTED).build();
    }
}
