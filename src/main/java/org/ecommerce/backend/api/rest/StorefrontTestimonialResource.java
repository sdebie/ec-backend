package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.TestimonialService;
import org.ecommerce.common.dto.TestimonialPublicDto;

import java.util.List;

/**
 * Public storefront endpoint returning published testimonials.
 * Explicitly {@code @PermitAll} so that access does not depend on annotation-absence.
 */
@Path("/api/storefront/testimonials")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class StorefrontTestimonialResource
{
    @Inject
    TestimonialService testimonialService;

    @GET
    public Response list()
    {
        List<TestimonialPublicDto> testimonials = testimonialService.findPublished();
        return Response.ok(testimonials).build();
    }
}
