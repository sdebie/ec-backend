package org.ecommerce.backend.api.rest;

import org.ecommerce.common.enums.ImageTypeEn;
import org.ecommerce.common.dto.ImageResponseDto;
import org.ecommerce.backend.service.ImageService;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Path("/api/admin/images")
public class ImageResource
{
    @Inject
    ImageService imageService;

    /**
     * Generic upload endpoint - saves file without creating database records
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@RestForm("file") FileUpload file)
    {
        try {
            String fileName = imageService.uploadImage(file);
            // Return the filename so React can save it in the Brand/Category record
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IOException e) {
            return Response.serverError().entity("Failed to save image").build();
        }
    }

    /**
     * Upload product image - saves file and creates ProductImageEntity record
     */
    @POST
    @Path("/upload/product/{productId}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadProductImage(
            @PathParam("productId") UUID productId,
            @RestForm("file") FileUpload file)
    {
        try {
            String fileName = imageService.uploadImage(file, ImageTypeEn.PRODUCT, productId);
            return Response.ok(new ImageResponseDto(fileName)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Product not found: " + e.getMessage())
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
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response bulkUpload(@RestForm("images") List<FileUpload> uploads) {
        return Response.ok(imageService.bulkUploadImages(uploads)).build();
    }

    @GET
    @Path("/image-list")
    public List<String> listImages() {
        return imageService.listImages();
    }

}