package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.PageContentService;
import org.ecommerce.common.dto.PageContentDto;

import java.time.OffsetDateTime;

/**
 * Public storefront endpoint for retrieving published page content by slug.
 * Explicitly declared {@code @PermitAll} so that access does not depend on
 * annotation-absence — legal content stays reachable even if a global auth
 * policy is later applied.
 */
@Path("/api/storefront/pages")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class StorefrontPageResource {

    @Inject
    PageContentService pageContentService;

    @GET
    @Path("/{slug}")
    public Response getBySlug(@PathParam("slug") String slug) {
        PageContentDto page = pageContentService.getPublishedBySlug(slug);
        if (page == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(new PublicPageResponse(
                page.slug(),
                page.title(),
                page.publishedContent(),
                page.publishedAt()
        )).build();
    }

    /**
     * Trimmed public response shape: only slug, title, content (= publishedContent), and publishedAt.
     */
    public record PublicPageResponse(
            String slug,
            String title,
            String content,
            OffsetDateTime publishedAt
    ) {}
}
