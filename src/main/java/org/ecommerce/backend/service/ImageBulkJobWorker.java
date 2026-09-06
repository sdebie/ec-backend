package org.ecommerce.backend.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.ImageBulkJobEntity;
import org.ecommerce.common.entity.ImageBulkJobItemEntity;
import org.ecommerce.common.enums.ImageBulkJobItemStatusEn;
import org.ecommerce.common.enums.ImageBulkJobStatusEn;
import org.ecommerce.common.repository.ImageBulkJobItemRepository;
import org.ecommerce.common.repository.ImageBulkJobRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background thumbnail + SKU-link for landed bulk images. Same accept-then-work
 * idea as {@code GenericImportAsyncService}, but not registered as a CSV strategy.
 */
@Slf4j
@ApplicationScoped
public class ImageBulkJobWorker
{
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "image-bulk-job");
        thread.setDaemon(true);
        return thread;
    });

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    ImageService imageService;

    @Inject
    ImageBulkJobRepository jobRepository;

    @Inject
    ImageBulkJobItemRepository itemRepository;

    Path stagingZipPath(UUID jobId)
    {
        return Paths.get(storagePath, ".staging", "image-jobs", jobId + ".zip");
    }

    public void enqueue(UUID jobId)
    {
        executor.submit(() -> processJob(jobId));
    }

    void processJob(UUID jobId)
    {
        try {
            Path stagingZip = stagingZipPath(jobId);
            if (Files.isRegularFile(stagingZip)) {
                unpackStagedZip(jobId, stagingZip);
                Files.deleteIfExists(stagingZip);
            }

            QuarkusTransaction.requiringNew().run(() -> {
                ImageBulkJobEntity job = jobRepository.findById(jobId);
                if (job != null && job.getStatus() == ImageBulkJobStatusEn.PENDING) {
                    job.setStatus(ImageBulkJobStatusEn.PROCESSING);
                }
            });

            List<UUID> pendingIds = QuarkusTransaction.requiringNew().call(
                    () -> itemRepository.listPendingIdsByJobId(jobId));
            for (UUID itemId : pendingIds) {
                processItem(itemId);
            }

            completeJob(jobId);
        } catch (Exception e) {
            log.error("Image bulk job {} failed", jobId, e);
            QuarkusTransaction.requiringNew().run(() -> {
                ImageBulkJobEntity job = jobRepository.findById(jobId);
                if (job == null) {
                    return;
                }
                job.setStatus(ImageBulkJobStatusEn.FAILED);
                job.setCompletedAt(Instant.now());
            });
        }
    }

    private void unpackStagedZip(UUID jobId, Path stagingZip) throws Exception
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ImageBulkJobEntity job = jobRepository.findById(jobId);
            if (job == null) {
                throw new IllegalStateException("Job not found: " + jobId);
            }
            String directory = job.getDestinationDirectory() == null ? "" : job.getDestinationDirectory();
            Path destinationRoot = imageService.resolveStorageDirectory(directory);
            try {
                ImageZipUnpacker.UnpackResult unpacked = ImageZipUnpacker.unpack(stagingZip, destinationRoot);
                job.setUploadedCount(unpacked.acceptedNames().size());
                job.setSkippedCount(unpacked.skippedNames().size());
                for (String name : unpacked.acceptedNames()) {
                    ImageBulkJobItemEntity item = new ImageBulkJobItemEntity();
                    item.setJob(job);
                    item.setRelativePath(directory.isBlank() ? name : directory + "/" + name);
                    item.setStatus(ImageBulkJobItemStatusEn.PENDING);
                    itemRepository.persist(item);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void processItem(UUID itemId)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ImageBulkJobItemEntity item = itemRepository.findById(itemId);
            if (item == null || item.getStatus() != ImageBulkJobItemStatusEn.PENDING) {
                return;
            }
            try {
                imageService.processLandedBulkImage(item.getRelativePath());
                item.setStatus(ImageBulkJobItemStatusEn.DONE);
            } catch (Exception e) {
                item.setStatus(ImageBulkJobItemStatusEn.FAILED);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                item.setError(message.length() > 1000 ? message.substring(0, 1000) : message);
                ImageBulkJobEntity job = item.getJob();
                job.setFailedCount(job.getFailedCount() + 1);
            }
        });
    }

    private void completeJob(UUID jobId)
    {
        QuarkusTransaction.requiringNew().run(() -> {
            ImageBulkJobEntity job = jobRepository.findById(jobId);
            if (job == null || job.getStatus() == ImageBulkJobStatusEn.FAILED) {
                return;
            }
            long failed = itemRepository.countByJobIdAndStatus(jobId, ImageBulkJobItemStatusEn.FAILED);
            job.setFailedCount((int) failed);
            job.setCompletedAt(Instant.now());
            if (failed > 0) {
                job.setStatus(ImageBulkJobStatusEn.COMPLETED_WITH_ERRORS);
            } else {
                job.setStatus(ImageBulkJobStatusEn.COMPLETED);
            }
        });
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
