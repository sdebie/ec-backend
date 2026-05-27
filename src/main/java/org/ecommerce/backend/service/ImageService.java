package org.ecommerce.backend.service;

import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.ecommerce.common.enums.ImageTypeEn;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImageService
{
    private static final Logger LOG = Logger.getLogger(ImageService.class);
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 200;

    public record PaginatedImagesResponse(List<String> images, int totalCount, int page, int pageSize) {
    }

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    EntityManager entityManager;

    /**
     * Generic upload method that saves the file and optionally creates database records
     * for product images.
     */
    public String uploadImage(FileUpload file) throws IOException
    {
        return uploadImage(file, null, null);
    }

    /**
     * Upload image with optional entity association for products.
     * For products, this will create a ProductImageEntity record.
     *
     * @param file      The file to upload
     * @param imageType The type of image (PRODUCT, CATEGORY, BRAND)
     * @param entityId  The ID of the entity (product, category, or brand)
     * @return The filename stored
     */
    @Transactional
    public String uploadImage(FileUpload file, ImageTypeEn imageType, UUID entityId) throws IOException
    {
        // 1. Ensure the directory exists (extra safety)
        Path root = Paths.get(storagePath);
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        // 2. Generate a unique filename using UUID
        String extension = getFileExtension(file.fileName());
        String newFileName = UUID.randomUUID() + extension;

        // 3. Move the uploaded temp file to your storage path
        Path targetPath = root.resolve(newFileName);
        Files.copy(file.filePath(), targetPath);

        // 3.5 Generate thumbnail for uploaded image
        createThumbnail(targetPath, newFileName);

        // 4. If image type is PRODUCT and entityId is provided, create ProductImageEntity
        if (imageType == ImageTypeEn.PRODUCT && entityId != null) {
            LOG.debugf("Creating ProductImageEntity for product=%s url=%s", entityId, newFileName);
            createProductImage(newFileName, entityId);
        }

        // 5. Return the filename (or relative path) to store in the DB
        return newFileName;
    }

    public Map<String, Integer> bulkUploadImages(List<FileUpload> uploads) {
        return bulkUploadImages(uploads, null);
    }

    public Map<String, Integer> bulkUploadImages(List<FileUpload> uploads, String destinationDirectory) {
        int uploadedCount = 0;
        int skippedCount = 0;

        if (uploads == null || uploads.isEmpty()) {
            return Map.of("uploaded", 0, "skipped", 0);
        }

        String normalizedDirectory = normalizeDestinationDirectory(destinationDirectory);
        Path destinationRoot = resolveStorageDirectory(normalizedDirectory);

        try {
            Files.createDirectories(destinationRoot);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create storage directory", e);
        }

        for (FileUpload file : uploads) {
            try {
                Path fullPath = Paths.get(file.fileName());
                String justTheFileName = fullPath.getFileName().toString();
                Path targetPath = destinationRoot.resolve(justTheFileName);
                String relativeFilePath = normalizedDirectory.isBlank()
                        ? justTheFileName
                        : normalizedDirectory + "/" + justTheFileName;

                // 2. ONLY save if it doesn't exist
                if (Files.notExists(targetPath)) {
                    Files.copy(file.filePath(), targetPath);
                    createThumbnail(targetPath, relativeFilePath);
                    uploadedCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                // Log error for specific file but continue the loop
                LOG.errorf(e, "Error saving file: %s", file.fileName());
            }
        }

        return Map.of(
                "uploaded", uploadedCount,
                "skipped", skippedCount
        );
    }

    private void createThumbnail(Path sourcePath, String fileName) throws IOException {
        Path thumbPath = Paths.get(storagePath, "thumbnails").resolve(fileName).normalize();
        Files.createDirectories(thumbPath.getParent());

        try {
            net.coobird.thumbnailator.Thumbnails.of(sourcePath.toFile())
                    .size(150, 150)
                    .outputQuality(0.8)
                    .toFile(thumbPath.toFile());
        } catch (Exception e) {
            // Some formats (e.g. WEBP without an ImageIO reader plugin) cannot be resized.
            LOG.warnf("Thumbnail resize failed for %s, using direct copy fallback. Reason: %s", fileName, e.getMessage());
            Files.copy(sourcePath, thumbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> listDestinationDirectories() {
        Path root = Paths.get(storagePath);

        if (Files.notExists(root)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .map(root::relativize)
                    .map(this::normalizeRelativePath)
                    .filter(relativePath -> !relativePath.equals("thumbnails") && !relativePath.startsWith("thumbnails/"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("Unable to list image destination directories", e);
            return Collections.emptyList();
        }
    }

    public List<String> listImages() {
        Path thumbnailsRoot = Paths.get(storagePath, "thumbnails");

        if (Files.notExists(thumbnailsRoot)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(thumbnailsRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(thumbnailsRoot::relativize)
                    .map(this::normalizeRelativePath)
                    .filter(relativePath -> relativePath.matches(".*\\.(jpg|jpeg|png|webp)$"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("Unable to list images from thumbnail storage", e);
            return Collections.emptyList();
        }
    }

    public PaginatedImagesResponse listImagesPaginated(Integer page, Integer pageSize, String search) {
        int safePage = page == null ? 0 : Math.max(0, page);
        int safePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase();

        List<String> filteredImages = listImages().stream()
                .filter(image -> normalizedSearch.isBlank() || image.toLowerCase().contains(normalizedSearch))
                .collect(Collectors.toList());

        int toIndex = Math.min(filteredImages.size(), (safePage + 1) * safePageSize);
        List<String> imagesPage = filteredImages.subList(0, toIndex);

        return new PaginatedImagesResponse(imagesPage, filteredImages.size(), safePage, safePageSize);
    }

    /**
     * Create a ProductImageEntity record in the database for the uploaded image.
     * The image will have the highest sort order.
     */
    private void createProductImage(String imageUrl, UUID productVariantId)
    {
        ProductVariantEntity productVariant = entityManager.find(ProductVariantEntity.class, productVariantId);
        if (productVariant == null) {
            throw new IllegalArgumentException("Product variant not found with id: " + productVariantId);
        }

        // Keep ordering within a variant-specific image list.
        Integer maxSortOrder = entityManager
                .createQuery("SELECT COALESCE(MAX(pi.sortOrder), 0) FROM ProductImageEntity pi WHERE pi.productVariant.id = :productVariantId", Integer.class)
                .setParameter("productVariantId", productVariantId)
                .getSingleResult();

        ProductImageEntity productImage = new ProductImageEntity();
        productImage.productVariant = productVariant;
        productImage.imageUrl = imageUrl;
        productImage.sortOrder = maxSortOrder + 1;
        productImage.isFeatured = maxSortOrder == 0; // First image is featured by default

        productImage.persist();
    }

    private String getFileExtension(String fileName)
    {
        if (fileName == null || !fileName.contains(".")) return ".jpg";
        return fileName.substring(fileName.lastIndexOf("."));
    }

    String normalizeDestinationDirectory(String destinationDirectory) {
        if (destinationDirectory == null || destinationDirectory.isBlank()) {
            return "";
        }

        String trimmed = destinationDirectory.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        if (trimmed.isBlank()) {
            return "";
        }

        Path normalized = Paths.get(trimmed).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Destination directory must stay within the configured storage path");
        }

        String relativeDirectory = normalizeRelativePath(normalized);
        if (relativeDirectory.equals(".")) {
            return "";
        }

        return relativeDirectory;
    }

    Path resolveStorageDirectory(String destinationDirectory) {
        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path destinationRoot = destinationDirectory == null || destinationDirectory.isBlank()
                ? storageRoot
                : storageRoot.resolve(destinationDirectory).normalize();

        if (!destinationRoot.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Destination directory must stay within the configured storage path");
        }

        return destinationRoot;
    }

    private String normalizeRelativePath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

}