package org.ecommerce.backend.api.rest;

import org.ecommerce.common.enums.ImageTypeEn;
import org.ecommerce.common.dto.ImageResponseDto;
import org.ecommerce.backend.service.ImageService;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/admin/images")
public class ImageResource
{
    @Inject
    ImageService imageService;

    /**
     * Generic upload endpoint - saves a file without creating database records.
     * Accepts an optional destinationDirectory to place the file in a subdirectory (e.g. "brands").
     */
    @POST
    @Path("/upload")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(
            @RestForm("file") FileUpload file,
            @RestForm("destinationDirectory") String destinationDirectory)
    {
        try {
            String fileName = (destinationDirectory != null && !destinationDirectory.isBlank())
                    ? imageService.uploadImageToDirectory(file, destinationDirectory)
                    : imageService.uploadImage(file);
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IOException e) {
            return Response.serverError().entity("Failed to save image").build();
        }
    }

    /**
     * Safe-delete endpoint for an uploaded image, used both by the abandoned-upload
     * cleanup flow (product edit/create, mid-form image removal) and by the admin
     * image library's delete action. Verifies the file path is not path-traversal-
     * vulnerable, checks whether ANY table capable of holding an image path still
     * references it (product images, brand logos, category images, store settings,
     * page content), and only deletes the physical file if no reference remains.
     */
    @DELETE
    @Path("/cleanup")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cleanup(CleanupRequest request)
    {
        if (request == null || request.filePath() == null || request.filePath().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "filePath is required"))
                    .build();
        }

        try {
            ImageService.CleanupOutcome outcome = imageService.cleanupUnassociatedFile(request.filePath());
            if (outcome.deleted()) {
                return Response.ok(Map.of("deleted", true)).build();
            } else {
                return Response.ok(Map.of("deleted", false, "reason", outcome.reason())).build();
            }
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", e.getMessage()))
                    .build();
        }
    }

    public record CleanupRequest(String filePath) {}

    /**
     * Upload product variant image - saves file and creates ProductImageEntity record.
     */
    @POST
    @Path("/upload/product-variant/{productVariantId}")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadProductVariantImage(
            @PathParam("productVariantId") UUID productVariantId,
            @RestForm("file") FileUpload file)
    {
        try {
            String fileName = imageService.uploadImage(file, ImageTypeEn.PRODUCT, productVariantId);
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Product variant not found: " + e.getMessage())
                    .build();
        } catch (IOException e) {
            return Response.serverError().entity("Failed to save image").build();
        }
    }

    /**
     * Upload category image - saves file without creating database record (handled by frontend)
     */
    @POST
    @Path("/upload/category")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadCategoryImage(@RestForm("file") FileUpload file)
    {
        try {
            String fileName = imageService.uploadImage(file, ImageTypeEn.CATEGORY, null);
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IOException e) {
            return Response.serverError().entity("Failed to save image").build();
        }
    }

    /**
     * Upload brand image - saves file without creating database record (handled by frontend)
     */
    @POST
    @Path("/upload/brand")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadBrandImage(@RestForm("file") FileUpload file)
    {
        try {
            String fileName = imageService.uploadImage(file, ImageTypeEn.BRAND, null);
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IOException e) {
            return Response.serverError().entity("Failed to save image").build();
        }
    }

    @POST
    @Path("/bulk-upload")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response bulkUpload(
            @RestForm("images") List<FileUpload> uploads,
            @RestForm("destinationDirectory") String destinationDirectory) {
        try {
            return Response.ok(imageService.bulkUploadImages(uploads, destinationDirectory)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/directories")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listDirectories() {
        return imageService.listDestinationDirectories();
    }

    @GET
    @Path("/image-list")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<String> listImages() {
        return imageService.listImages();
    }

    @GET
    @Path("/image-list/paginated")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    @Produces(MediaType.APPLICATION_JSON)
    public ImageService.PaginatedImagesResponse listImagesPaginated(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("30") int pageSize,
            @QueryParam("search") @DefaultValue("") String search,
            @QueryParam("directory") @DefaultValue("") String directory) {
        return imageService.listImagesPaginated(page, pageSize, search, directory);
    }

}