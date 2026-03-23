package org.ecommerce.backend.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.ecommerce.backend.service.ProductExportService;
import org.jboss.logging.Logger;

import java.io.PrintWriter;

@Path("/api/admin/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class ProductExport {
    private static final Logger LOG = Logger.getLogger(ProductExport.class);

    @Inject
    ProductExportService exportService;

    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportAllProducts() {
        StreamingOutput stream = output -> {
            try (PrintWriter writer = new PrintWriter(output)) {
                exportService.writeProductsCsv(writer);
            } catch (Exception e) {
                LOG.error("Error exporting products to CSV", e);
                throw e;
            }
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=catalog_export.csv")
                .build();
    }
}
