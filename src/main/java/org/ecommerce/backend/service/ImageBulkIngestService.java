package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.dto.ImageUploadJobDto;
import org.ecommerce.common.entity.ImageBulkJobEntity;
import org.ecommerce.common.entity.ImageBulkJobItemEntity;
import org.ecommerce.common.enums.ImageBulkJobItemStatusEn;
import org.ecommerce.common.enums.ImageBulkJobStatusEn;
import org.ecommerce.common.repository.ImageBulkJobItemRepository;
import org.ecommerce.common.repository.ImageBulkJobRepository;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ImageBulkIngestService
{
    @Inject
    ImageService imageService;

    @Inject
    ImageBulkJobRepository jobRepository;

    @Inject
    ImageBulkJobItemRepository itemRepository;

    @Inject
    ImageBulkJobWorker worker;

    @Transactional
    public ImageBulkIngestResponse ingestFiles(List<FileUpload> uploads, String destinationDirectory)
    {
        ImageService.BulkLandResult landed = imageService.landBulkImages(uploads, destinationDirectory);
        ImageBulkJobEntity job = createJob(destinationDirectory, landed.acceptedNames().size(), landed.skippedNames().size());
        for (String relativePath : landed.acceptedRelativePaths()) {
            persistItem(job, relativePath);
        }
        return new ImageBulkIngestResponse(job.getId(), landed.acceptedNames(), landed.skippedNames());
    }

    @Transactional
    public ImageBulkIngestResponse ingestZip(FileUpload zip, String destinationDirectory) throws IOException
    {
        if (zip == null || zip.filePath() == null) {
            throw new IllegalArgumentException("Zip file is required");
        }
        ImageBulkJobEntity job = createJob(destinationDirectory, 0, 0);
        Path staging = worker.stagingZipPath(job.getId());
        Files.createDirectories(staging.getParent());
        Files.copy(zip.filePath(), staging);
        return new ImageBulkIngestResponse(job.getId(), List.of(), List.of());
    }

    public void enqueue(UUID jobId)
    {
        worker.enqueue(jobId);
    }

    public ImageUploadJobDto findJob(UUID jobId)
    {
        ImageBulkJobEntity job = jobRepository.findById(jobId);
        if (job == null) {
            return null;
        }
        long processed = itemRepository.countByJobIdAndStatus(jobId, ImageBulkJobItemStatusEn.DONE);
        List<String> errors = itemRepository.listErrorSamples(jobId, 10);
        String directory = job.getDestinationDirectory() == null ? "" : job.getDestinationDirectory();
        return new ImageUploadJobDto(
                job.getId().toString(),
                job.getStatus().name(),
                directory,
                job.getUploadedCount() == null ? 0 : job.getUploadedCount(),
                (int) processed,
                job.getFailedCount() == null ? 0 : job.getFailedCount(),
                job.getSkippedCount() == null ? 0 : job.getSkippedCount(),
                errors
        );
    }

    private ImageBulkJobEntity createJob(String destinationDirectory, int uploaded, int skipped)
    {
        ImageBulkJobEntity job = new ImageBulkJobEntity();
        job.setDestinationDirectory(imageService.normalizeDestinationDirectory(destinationDirectory));
        job.setStatus(ImageBulkJobStatusEn.PENDING);
        job.setUploadedCount(uploaded);
        job.setSkippedCount(skipped);
        job.setFailedCount(0);
        jobRepository.persist(job);
        return job;
    }

    private void persistItem(ImageBulkJobEntity job, String relativePath)
    {
        ImageBulkJobItemEntity item = new ImageBulkJobItemEntity();
        item.setJob(job);
        item.setRelativePath(relativePath);
        item.setStatus(ImageBulkJobItemStatusEn.PENDING);
        itemRepository.persist(item);
    }
}
