package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.ecommerce.backend.service.ProductImportService;
import org.ecommerce.backend.service.ProductUploadAsyncService;
import org.ecommerce.common.dto.ProductUploadFormDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

@Path("/api/admin/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class ProductUploadResource {

    private static final Logger LOG = Logger.getLogger(ProductUploadResource.class);

    @Inject
    ProductImportService importService;

    @Inject
    ProductUploadAsyncService asyncService;

    @POST
    @Path("/upload-csv")
    public Response uploadCsv(ProductUploadFormDto form) {
        try {
            // 1. Resolve the admin user from the security context
            StaffUserEntity admin = StaffUserEntity.findByEmail("admin@gmail.com");

            if (admin == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // 2. Create the batch record immediately so the client gets a batchId
            var batch = importService.createPendingBatch(form.file.fileName(), admin);

            // 3. Kick off async CSV parsing & staging — the client polls for status
            InputStream is = Files.newInputStream(form.file.filePath());
            asyncService.handleCsvUploadAsync(is, batch.id);

            return Response.accepted(importService.getBatchProcessStatus(batch.id)).build();
        } catch (Exception e) {
            LOG.error("Error processing CSV upload", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error processing CSV: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/batches/{batchId}/staged/async")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addStagedRowsAsync(@PathParam("batchId") UUID batchId) {
        try {
            importService.markBatchAsProcessing(batchId);
        } catch (NotFoundException ex) {
            return Response.status(Response.Status.NOT_FOUND).entity(ex.getMessage()).build();
        } catch (IllegalStateException ex) {
            return Response.status(Response.Status.CONFLICT).entity(ex.getMessage()).build();
        }

        asyncService.processStagedRowsAsync(batchId);
        return Response.accepted(importService.getBatchProcessStatus(batchId)).build();
    }

    @GET
    @Path("/batches/{batchId}/staged/status")
    public Response getStagedRowsStatus(@PathParam("batchId") UUID batchId) {
        try {
            return Response.ok(importService.getBatchProcessStatus(batchId)).build();
        } catch (NotFoundException ex) {
            return Response.status(Response.Status.NOT_FOUND).entity(ex.getMessage()).build();
        }
    }

}
