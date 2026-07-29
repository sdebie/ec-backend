package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.TestimonialService;
import org.ecommerce.common.dto.CreateTestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.ecommerce.common.dto.UpdateTestimonialRequest;

import java.util.List;
import java.util.UUID;

@Path("/api/admin/testimonials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminTestimonialResource
{
    @Inject
    TestimonialService testimonialService;

    @GET
    @RolesAllowed({"SUPER_ADMIN", "VIEWER"})
    public Response list()
    {
        List<TestimonialDto> testimonials = testimonialService.findAll();
        return Response.ok(testimonials).build();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"SUPER_ADMIN", "VIEWER"})
    public Response getById(@PathParam("id") UUID id)
    {
        TestimonialDto dto = testimonialService.getById(id);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Testimonial not found"))
                    .build();
        }
        return Response.ok(dto).build();
    }

    @POST
    @RolesAllowed("SUPER_ADMIN")
    public Response create(@Valid CreateTestimonialRequest request)
    {
        TestimonialDto dto = testimonialService.create(request);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("SUPER_ADMIN")
    public Response update(@PathParam("id") UUID id, @Valid UpdateTestimonialRequest request)
    {
        TestimonialDto dto = testimonialService.update(id, request);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Testimonial not found"))
                    .build();
        }
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("SUPER_ADMIN")
    public Response delete(@PathParam("id") UUID id)
    {
        boolean deleted = testimonialService.delete(id);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Testimonial not found"))
                    .build();
        }
        return Response.noContent().build();
    }

    public record ErrorResponse(String message) {}
}
