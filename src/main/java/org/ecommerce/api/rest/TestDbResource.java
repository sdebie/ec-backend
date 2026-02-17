package org.ecommerce.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.persistance.entity.TestEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple REST endpoint to verify that the PostgreSQL table "test" exists and is accessible
 * in the configured database (kw_db). Delegates the check to the JPA entity.
 */
@Path("/testdb")
public class TestDbResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkTestTable() {
        Map<String, Object> payload = new HashMap<>();
        try {
            boolean exists = TestEntity.isTableAvailable();
            payload.put("ok", exists);
            payload.put("table", "public.test");
            payload.put("message", exists ? "Table is available" : "Table not found");
            return Response.ok(payload).build();
        } catch (Exception e) {
            payload.put("ok", false);
            payload.put("table", "public.test");
            payload.put("message", "Failed to query database");
            payload.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(payload).build();
        }
    }
}
