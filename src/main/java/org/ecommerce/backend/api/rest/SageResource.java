package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.service.sage.SageApiClient;
import org.ecommerce.backend.service.sage.SageApiException;
import org.ecommerce.backend.service.sage.SageItemSyncService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-facing proxy to the Sage Accounting reseller API, e.g.
 * {@code GET /api/admin/sage?type=Item/Get&$filter=Modified ge datetime'2026-08-04'}.
 * Credentials never reach the browser — {@link SageApiClient} injects them server-side.
 * For {@code type=Item/Get}, the first page is fetched synchronously (so the caller
 * gets an immediate success/failure) and the remaining pages are then paged through
 * and logged in the background by {@link SageItemSyncService}.
 */
@Path("/api/admin/sage")
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
public class SageResource
{
    @Inject
    SageApiClient sageApiClient;

    @Inject
    SageItemSyncService sageItemSyncService;

    @GET
    //@RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response call(@QueryParam("type") String type, @Context UriInfo uriInfo)
    {
        log.debug("Sage Connection Test");
        if (type == null || type.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"errors\":[{\"message\":\"Query param 'type' is required, e.g. type=Item/Get\"}]}")
                    .build();
        }

        Map<String, String> extraParams = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : uriInfo.getQueryParameters().entrySet()) {
            if (!"type".equals(entry.getKey()) && !entry.getValue().isEmpty()) {
                extraParams.put(entry.getKey(), entry.getValue().get(0));
            }
        }

        boolean isItemSync = type.startsWith("Item");
        if (isItemSync) {
            extraParams.putIfAbsent("$top", "10");
        }

        try {
            String body = sageApiClient.call(type, extraParams);
            if (isItemSync) {
                sageItemSyncService.syncItemsAsync(type, extraParams, body);
                return Response.ok("{\"message\":\"Sage Item sync started\"}").build();
            }
            return Response.ok(body).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"errors\":[{\"message\":\"" + e.getMessage() + "\"}]}")
                    .build();
        } catch (SageApiException e) {
            Response.Status status = e.getStatusCode() >= 400 && e.getStatusCode() < 600
                    ? Response.Status.fromStatusCode(e.getStatusCode())
                    : Response.Status.BAD_GATEWAY;
            return Response.status(status != null ? status : Response.Status.BAD_GATEWAY)
                    .entity(e.getResponseBody() != null
                            ? e.getResponseBody()
                            : "{\"errors\":[{\"message\":\"Sage API unavailable\"}]}")
                    .build();
        }
    }
}
