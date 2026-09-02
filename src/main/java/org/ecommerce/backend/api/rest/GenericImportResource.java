package org.ecommerce.backend.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.import_engine.GenericImportAsyncService;
import org.ecommerce.backend.service.import_engine.ProductImportOrchestrator;
import org.ecommerce.backend.service.import_engine.ProductPriceImportOrchestrator;
import org.ecommerce.common.dto.ProductUploadFormDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Single resource for all import types (CSV, Sage, etc).
 * Eliminates endpoint duplication and supports any import strategy.
 */
@Path("/api/admin/imports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class GenericImportResource {
    private static final Logger LOG = Logger.getLogger(GenericImportResource.class);

    @Inject
    JsonWebToken jwt;

    @Inject
    GenericImportAsyncService asyncService;

    @Inject
    ProductImportOrchestrator productOrchestrator;

    @Inject
    ProductPriceImportOrchestrator priceOrchestrator;

    /**
     * Upload a file for import.
     * Type can be: "product", "price", "sage", etc.
     */
    @POST
    @Path("/{importType}/upload")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response uploadImportFile(@PathParam("importType") String importType, ProductUploadFormDto form) {
        try {
            StaffUserEntity admin = StaffUserEntity.findByEmail(jwt.getName());
            if (admin == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // Create pending batch
            var batch = switch (importType) {
                case "product" -> productOrchestrator.createPendingBatch(form.getFile().fileName(), admin);
                case "price" -> priceOrchestrator.createPendingBatch(form.getFile().fileName(), admin);
                default -> throw new IllegalArgumentException("Unknown import type: " + importType);
            };

            // Kick off async staging
            String strategyType = getStrategyType(importType);
            InputStream is = Files.newInputStream(form.getFile().filePath());
            asyncService.stageRowsAsync(strategyType, is, batch.getId());

            // Return batch status
            var status = switch (importType) {
                case "product" -> productOrchestrator.getStatus(batch.getId());
                case "price" -> priceOrchestrator.getStatus(batch.getId());
                default -> null;
            };

            return Response.accepted(status).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error processing %s upload", importType);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error processing upload: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Start processing staged rows.
     */
    @POST
    @Path("/{importType}/batches/{batchId}/process")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response startProcessing(@PathParam("importType") String importType, @PathParam("batchId") UUID batchId) {
        try {
            StaffUserEntity approver = StaffUserEntity.findByEmail(jwt.getName());
            if (approver == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // Mark batch as processing
            switch (importType) {
                case "product" -> productOrchestrator.markAsProcessing(batchId);
                case "price" -> priceOrchestrator.markAsProcessing(batchId, approver);
                default -> throw new IllegalArgumentException("Unknown import type: " + importType);
            }

            // Get strategy type and process
            String strategyType = getStrategyType(importType);
            asyncService.processRowsAsync(strategyType, batchId);

            // Return updated status
            var status = switch (importType) {
                case "product" -> productOrchestrator.getStatus(batchId);
                case "price" -> priceOrchestrator.getStatus(batchId);
                default -> null;
            };

            return Response.accepted(status).build();
        } catch (NotFoundException ex) {
            return Response.status(Response.Status.NOT_FOUND).entity(ex.getMessage()).build();
        } catch (IllegalStateException ex) {
            return Response.status(Response.Status.CONFLICT).entity(ex.getMessage()).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error processing %s batch %s", importType, batchId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error processing batch: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Poll for batch status.
     */
    @GET
    @Path("/{importType}/batches/{batchId}/status")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response getBatchStatus(@PathParam("importType") String importType, @PathParam("batchId") UUID batchId) {
        try {
            var status = switch (importType) {
                case "product", "sage-items" -> productOrchestrator.getStatus(batchId);
                case "price", "sage" -> priceOrchestrator.getStatus(batchId);
                default -> throw new IllegalArgumentException("Unknown import type: " + importType);
            };

            return Response.ok(status).build();
        } catch (NotFoundException ex) {
            return Response.status(Response.Status.NOT_FOUND).entity(ex.getMessage()).build();
        }
    }

    /**
     * Trigger a Sage price import (without file upload).
     * Connects directly to Sage API to fetch current prices using SageApiClient.
     */
    @POST
    @Path("/sage/upload")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response triggerSageImport() {
        try {
            StaffUserEntity admin = StaffUserEntity.findByEmail(jwt.getName());
            if (admin == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // Create a batch for this Sage import
            var batch = priceOrchestrator.createPendingBatch("Sage Price Import", admin);

            // Kick off async Sage fetch (pass null inputstream since Sage fetches from API)
            asyncService.stageRowsAsync("sage", null, batch.getId());

            // Return batch status
            var status = priceOrchestrator.getStatus(batch.getId());
            return Response.accepted(status).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error starting Sage import");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error starting Sage price import: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Trigger a Sage item import (without file upload).
     * Connects directly to Sage API to fetch item data using SageApiClient.
     * Handles pagination automatically.
     */
    @POST
    @Path("/sage-items/upload")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public Response triggerSageItemImport() {
        try {
            StaffUserEntity admin = StaffUserEntity.findByEmail(jwt.getName());
            if (admin == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // Create a batch for this Sage item import
            var batch = productOrchestrator.createPendingBatch("Sage Item Import", admin);

            // Kick off async Sage item fetch (pass null inputstream since Sage fetches from API)
            asyncService.stageRowsAsync("sage-items", null, batch.getId());

            // Return batch status
            var status = productOrchestrator.getStatus(batch.getId());
            return Response.accepted(status).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error starting Sage item import");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error starting Sage item import: " + e.getMessage())
                    .build();
        }
    }

    private String getStrategyType(String importType) {
        return switch (importType) {
            case "product" -> "product-csv";
            case "price" -> "price-csv";
            case "sage" -> "sage";
            case "sage-items" -> "sage-items";
            default -> throw new IllegalArgumentException("Unknown import type: " + importType);
        };
    }

}
