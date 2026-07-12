package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.PageContentService;
import org.ecommerce.common.dto.PageContentDto;
import org.ecommerce.common.dto.PageContentSummaryDto;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@Path("/api/admin/pages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminPageContentResource {

    private static final Logger LOG = Logger.getLogger(AdminPageContentResource.class);

    @Inject
    PageContentService pageContentService;

    /**
     * Request body for PUT (save draft).
     */
    public record SaveDraftRequest(String content) {}

    /**
     * List page summaries by category. No content fields included.
     */
    @GET
    @RolesAllowed({"SUPER_ADMIN", "VIEWER"})
    public Response listByCategory(@QueryParam("category") String category) {
        List<PageContentSummaryDto> summaries = pageContentService.listByCategory(category);
        return Response.ok(summaries).build();
    }

    /**
     * Get a single page by id — full content (both draft and published fields).
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({"SUPER_ADMIN", "VIEWER"})
    public Response getById(@PathParam("id") UUID id) {
        PageContentDto dto = pageContentService.getById(id);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dto).build();
    }

    /**
     * Save a draft — sanitises content, writes to draft_content, does NOT touch published state.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed("SUPER_ADMIN")
    public Response saveDraft(@PathParam("id") UUID id, SaveDraftRequest request) {
        if (request == null || request.content() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body with 'content' field is required")
                    .build();
        }

        PageContentDto dto = pageContentService.saveDraft(id, request.content());
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dto).build();
    }

    /**
     * Publish a page — copies draft_content → published_content and stamps published_at.
     */
    @POST
    @Path("/{id}/publish")
    @RolesAllowed("SUPER_ADMIN")
    public Response publish(@PathParam("id") UUID id) {
        PageContentDto dto = pageContentService.publish(id);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dto).build();
    }
}
