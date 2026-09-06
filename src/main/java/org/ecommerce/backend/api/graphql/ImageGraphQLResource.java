package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.dto.ImageUploadJobDto;
import org.ecommerce.backend.service.ImageBulkIngestService;
import org.ecommerce.backend.service.ImageService;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class ImageGraphQLResource
{
    @Inject
    ImageService imageService;

    @Inject
    ImageBulkIngestService imageBulkIngestService;

    @Query("existingImageFilenames")
    @Description("Filenames that already exist as original files in the destination directory. Staff JWT required.")
    @Transactional(TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<String> existingImageFilenames(
            @Name("directory") String directory,
            @Name("filenames") List<String> filenames)
    {
        return imageService.findExistingFilenames(directory, filenames);
    }

    @Query("imageUploadJob")
    @Description("Bulk image ingest job status for the admin dialog poll. Staff JWT required.")
    @Transactional(TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public ImageUploadJobDto imageUploadJob(@Name("id") String id) throws GraphQLException
    {
        UUID jobId;
        try {
            jobId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Image upload job not found");
        }
        ImageUploadJobDto job = imageBulkIngestService.findJob(jobId);
        if (job == null) {
            throw new GraphQLException("Image upload job not found");
        }
        return job;
    }
}
