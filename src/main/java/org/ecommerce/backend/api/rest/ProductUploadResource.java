package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.ecommerce.backend.service.ProductImportService;
import org.ecommerce.common.dto.ProductUploadFormDto;
import org.ecommerce.common.entity.StaffUserEntity;

import java.io.InputStream;
import java.nio.file.Files;

@Path("/api/admin/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class ProductUploadResource {

    @Inject
    ProductImportService importService;

    @POST
    @Path("/upload-csv")
    public Response uploadCsv(ProductUploadFormDto form) {
        try {
            // 1. Resolve the admin user from the security context
            StaffUserEntity admin = StaffUserEntity.find("username", "admin").firstResult();

            if (admin == null) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }

            // 2. Process the file
            try (InputStream is = Files.newInputStream(form.file.filePath())) {
                var batch = importService.handleCsvUpload(
                        is,
                        form.file.fileName(),
                        admin
                );
                return Response.ok(batch).build();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error processing CSV: " + e.getMessage())
                    .build();
        }
    }
}
