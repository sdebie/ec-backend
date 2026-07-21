package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.QuoteRequestService;
import org.ecommerce.backend.service.RateLimitDecision;
import org.ecommerce.backend.service.RateLimiterService;
import org.ecommerce.backend.utils.ClientIpUtils;
import org.ecommerce.common.dto.QuoteRequestSubmissionDto;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Public REST endpoint for storefront quote request submissions.
 * <p>
 * No auth annotation — public by design, same as {@link ContactEnquiryResource}.
 * <p>
 * Flow: validate → honeypot check → rate-limit → persist + async mail → 201.
 * <p>
 * Status codes:
 * <ul>
 *   <li>{@code 201} — created (request persisted; also returned for honeypot traps)</li>
 *   <li>{@code 422} — validation failure (null body, constraint violations, or unknown variant)</li>
 *   <li>{@code 429} — rate-limited</li>
 * </ul>
 * <p>
 * Deliberately diverges from ContactEnquiryResource: returns 201 (not 202) because
 * this is a persist-then-notify flow. Honeypot also returns 201 (indistinguishable
 * from success). Mail failure never fails the submission.
 */
@Path("/api/storefront/quote-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuoteRequestResource {

    private static final Logger LOG = Logger.getLogger(QuoteRequestResource.class);

    @Inject
    QuoteRequestService quoteRequestService;

    @Inject
    RateLimiterService rateLimiterService;

    @Inject
    Validator validator;

    @POST
    public Response submit(
            QuoteRequestSubmissionDto dto,
            @HeaderParam("CF-Connecting-IP") String cfConnectingIp,
            @HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("X-Real-IP") String xRealIp
    ) {
        // 1. Validation — 422 for null body or constraint violations
        if (dto == null) {
            return Response.status(422).build();
        }
        Set<ConstraintViolation<QuoteRequestSubmissionDto>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            return Response.status(422).build();
        }

        // 2. Resolve client IP (same precedence as ContactEnquiryResource)
        String clientIp = ClientIpUtils.resolveClientIp(cfConnectingIp, xForwardedFor, xRealIp);

        // 3. Honeypot check — non-empty means bot; silent accept (201), persist nothing
        if (dto.website() != null && !dto.website().isBlank()) {
            LOG.infof("[QuoteRequest] honeypot triggered (IP: %s)", clientIp);
            return Response.status(Response.Status.CREATED).build();
        }

        // 4. Rate-limit check — keyed by IP only; denial log never contains email
        RateLimitDecision decision = rateLimiterService.check("quote-request", clientIp, 5, 3600);
        if (!decision.allowed()) {
            return Response.status(429).header("Retry-After", decision.retryAfterSeconds()).build();
        }

        // 5. Submit — persist + post-commit async mail; unknown variant → 422
        try {
            quoteRequestService.submit(dto);
        } catch (IllegalArgumentException e) {
            // Unknown variantId surfaced from service-level check
            return Response.status(422).build();
        }

        return Response.status(Response.Status.CREATED).build();
    }
}
