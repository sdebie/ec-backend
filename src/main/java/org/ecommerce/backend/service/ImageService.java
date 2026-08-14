package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.ImageTypeEn;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@ApplicationScoped
public class ImageService
{
    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 200;

    public record PaginatedImagesResponse(List<String> images, int totalCount, int page, int pageSize)
    {
    }

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    EntityManager entityManager;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    org.ecommerce.common.repository.ProductImageRepository productImageRepository;

    /**
     * Generic upload method that saves the file to root storage.
     */
    public String uploadImage(FileUpload file) throws IOException
    {
        return uploadImage(file, null, null);
    }

    /**
     * Upload a single file to a specific subdirectory within storage.
     * Uses the same path-traversal guards as bulk upload.
     */
    public String uploadImageToDirectory(FileUpload file, String destinationDirectory) throws IOException
    {
        String normalizedDirectory = normalizeDestinationDirectory(destinationDirectory);
        Path destinationRoot = resolveStorageDirectory(normalizedDirectory);

        if (!Files.exists(destinationRoot)) {
            Files.createDirectories(destinationRoot);
        }

        String extension = getFileExtension(file.fileName());
        String newFileName = UUID.randomUUID() + extension;
        Path targetPath = destinationRoot.resolve(newFileName);
        Files.copy(file.filePath(), targetPath);

        // Path relative to storage root, so resolveImageUrl() builds the correct URL and the
        // thumbnail mirrors the same directory listImages()/listImagesPaginated() walk.
        String relativeFilePath = normalizedDirectory.isBlank() ? newFileName : normalizedDirectory + "/" + newFileName;
        createThumbnail(targetPath, relativeFilePath);

        return relativeFilePath;
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
            log.debug("Creating ProductImageEntity for product={} url={}", entityId, newFileName);
            createProductImageIfAbsent(newFileName, entityId);
        }

        // 5. Return the filename (or relative path) to store in the DB
        return newFileName;
    }

    public Map<String, Integer> bulkUploadImages(List<FileUpload> uploads)
    {
        return bulkUploadImages(uploads, null);
    }

    public Map<String, Integer> bulkUploadImages(List<FileUpload> uploads, String destinationDirectory)
    {
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
                String relativeFilePath = normalizedDirectory.isBlank() ? justTheFileName : normalizedDirectory + "/" + justTheFileName;

                // 2. ONLY save if it doesn't exist
                if (Files.notExists(targetPath)) {
                    Files.copy(file.filePath(), targetPath);
                    createThumbnail(targetPath, relativeFilePath);
                    uploadedCount++;
                    // Link to product variant when filename matches a known SKU
                    String sku = stripExtension(justTheFileName);
                    tryLinkBulkImageToVariant(relativeFilePath, sku);
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                // Log error for specific file but continue the loop
                log.error("Error saving file: {}", file.fileName());
            }
        }

        return Map.of("uploaded", uploadedCount, "skipped", skippedCount);
    }

    private void createThumbnail(Path sourcePath, String fileName) throws IOException
    {
        Path thumbPath = Paths.get(storagePath, "thumbnails").resolve(fileName).normalize();
        Files.createDirectories(thumbPath.getParent());

        try {
            net.coobird.thumbnailator.Thumbnails.of(sourcePath.toFile())
                    .size(150, 150)
                    .outputQuality(0.8)
                    .toFile(thumbPath.toFile());
        } catch (Exception e) {
            // Some formats (e.g. WEBP without an ImageIO reader plugin) cannot be resized.
            log.warn("Thumbnail resize failed for {}, using direct copy fallback. Reason: {}", fileName, e.getMessage());
            Files.copy(sourcePath, thumbPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<String> listDestinationDirectories()
    {
        Path root = Paths.get(storagePath);

        if (Files.notExists(root)) {
            log.error("Unable to list image destination directories for path:{}", root);
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(root)) {
            List<String> directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .map(root::relativize)
                    .map(this::normalizeRelativePath)
                    .filter(relativePath -> !relativePath.equals("thumbnails") && !relativePath.startsWith("thumbnails/"))
                    .sorted()
                    .collect(Collectors.toList());
            log.debug("Found {} destination directories.", directories.size());
            return directories;
        } catch (IOException e) {
            log.error("Unable to list image destination directories", e);
            return Collections.emptyList();
        }

    }

    public List<String> listImages()
    {
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
            log.error("Unable to list images from thumbnail storage", e);
            return Collections.emptyList();
        }
    }

    public PaginatedImagesResponse listImagesPaginated(Integer page, Integer pageSize, String search)
    {
        return listImagesPaginated(page, pageSize, search, null);
    }

    /**
     * @param directory optional subfolder prefix (e.g. "brands") — ANDed with search, matched
     *                  as a leading path segment rather than a substring so a directory named
     *                  "brands" never matches a filename that merely contains that text.
     */
    public PaginatedImagesResponse listImagesPaginated(Integer page, Integer pageSize, String search, String directory)
    {
        int safePage = page == null ? 0 : Math.max(0, page);
        int safePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase();
        String directoryPrefix = normalizeSearchDirectory(directory);

        List<String> filteredImages = listImages()
                .stream()
                .filter(image -> normalizedSearch.isBlank() || image.toLowerCase().contains(normalizedSearch))
                .filter(image -> directoryPrefix.isEmpty() || image.toLowerCase().startsWith(directoryPrefix))
                .collect(Collectors.toList());

        int fromIndex = Math.min(filteredImages.size(), safePage * safePageSize);
        int toIndex = Math.min(filteredImages.size(), fromIndex + safePageSize);
        List<String> imagesPage = filteredImages.subList(fromIndex, toIndex);

        return new PaginatedImagesResponse(imagesPage, filteredImages.size(), safePage, safePageSize);
    }

    /**
     * Read-only normalization for the directory query filter — trims/lower-cases and ensures
     * exactly one trailing slash so it matches as a leading path segment. Unlike
     * normalizeDestinationDirectory(), this never touches the filesystem or throws on a
     * traversal-looking value: an unmatchable directory filter just yields zero results.
     */
    private String normalizeSearchDirectory(String directory)
    {
        if (directory == null || directory.isBlank()) {
            return "";
        }

        String trimmed = directory.trim().toLowerCase().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed.isBlank() ? "" : trimmed + "/";
    }

    @Transactional
    public void tryLinkBulkImageToVariant(String relativeFilePath, String sku)
    {
        try {
            ProductVariantEntity variant = productVariantRepository.findBySku(sku);
            if (variant != null) {
                createProductImageIfAbsent(relativeFilePath, variant.getId());
                log.debug("Linked bulk image {} to variant SKU={}", relativeFilePath, sku);
            }
        } catch (Exception e) {
            log.warn("Could not link bulk image {} to SKU={}: {}", relativeFilePath, sku, e.getMessage());
        }
    }

    /**
     * Retries SKU-based bulk-image association after a product CSV creates the
     * variant. Bulk image upload is allowed to precede product import.
     */
    @Transactional
    public void linkExistingBulkImagesForVariant(ProductVariantEntity variant)
    {
        if (variant == null || variant.getId() == null || variant.getSku() == null || variant.getSku().isBlank()) {
            return;
        }

        for (String imagePath : findBulkImagePathsForSku(variant.getSku())) {
            createProductImageIfAbsent(imagePath, variant.getId());
        }
    }

    List<String> findBulkImagePathsForSku(String sku)
    {
        if (sku == null || sku.isBlank()) {
            return List.of();
        }

        Path root = Paths.get(storagePath);
        if (Files.notExists(root)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(this::normalizeRelativePath)
                    .filter(path -> !path.startsWith("thumbnails/"))
                    .filter(path -> sku.equals(stripExtension(Paths.get(path).getFileName().toString())))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not scan bulk images for SKU {}: {}", sku, e.getMessage());
            return List.of();
        }
    }

    /**
     * Create a ProductImageEntity record in the database for the uploaded image.
     * The image will have the highest sort order.
     */
    private void createProductImageIfAbsent(String imageUrl, UUID productVariantId)
    {
        ProductVariantEntity productVariant = entityManager.find(ProductVariantEntity.class, productVariantId);
        if (productVariant == null) {
            throw new IllegalArgumentException("Product variant not found with id: " + productVariantId);
        }

        Long existingCount = entityManager
                .createQuery("SELECT COUNT(pi) FROM ProductImageEntity pi WHERE pi.productVariant.id = :productVariantId AND pi.imageUrl = :imageUrl", Long.class)
                .setParameter("productVariantId", productVariantId)
                .setParameter("imageUrl", imageUrl)
                .getSingleResult();
        if (existingCount != null && existingCount > 0) {
            return;
        }

        // Keep ordering within a variant-specific image list.
        Integer maxSortOrder = entityManager
                .createQuery("SELECT COALESCE(MAX(pi.sortOrder), 0) FROM ProductImageEntity pi WHERE pi.productVariant.id = :productVariantId", Integer.class)
                .setParameter("productVariantId", productVariantId)
                .getSingleResult();

        ProductImageEntity productImage = new ProductImageEntity();
        productImage.setProductVariant(productVariant);
        productImage.setImageUrl(imageUrl);
        productImage.setSortOrder(maxSortOrder + 1);
        productImage.setIsFeatured(maxSortOrder == 0); // First image is featured by default

        productImage.persist();
    }

    /**
     * Safely delete an uploaded file if no ProductImageEntity references it.
     * Validates the path is not traversal-vulnerable before checking associations.
     *
     * @param filePath storage-relative path (e.g. "abc123.jpg" or "products/abc123.jpg")
     * @return true if the file was deleted, false if it is still associated
     * @throws IllegalArgumentException if the path is invalid or traversal-vulnerable
     */
    @Transactional
    public boolean cleanupUnassociatedFile(String filePath)
    {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }

        // Validate path safety — reject traversal attempts
        Path normalized = Paths.get(filePath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Invalid file path: traversal detected");
        }
        String safePath = normalized.toString().replace(File.separatorChar, '/');
        if (safePath.contains("..")) {
            throw new IllegalArgumentException("Invalid file path: traversal detected");
        }

        // Check if any ProductImageEntity references this path
        Long refCount = entityManager
                .createQuery("SELECT COUNT(pi) FROM ProductImageEntity pi WHERE pi.imageUrl = :path", Long.class)
                .setParameter("path", safePath)
                .getSingleResult();

        if (refCount != null && refCount > 0) {
            log.debug("Cannot cleanup file {} — still referenced by {} product image(s)", safePath, refCount);
            return false;
        }

        // Delete the physical file and its thumbnail
        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path targetFile = storageRoot.resolve(safePath).normalize();

        // Final safety check — must stay within storage root
        if (!targetFile.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file path: outside storage root");
        }

        boolean deleted = false;
        try {
            if (Files.exists(targetFile)) {
                Files.delete(targetFile);
                deleted = true;
            }
            // Also clean up thumbnail
            Path thumbnailFile = storageRoot.resolve("thumbnails").resolve(safePath).normalize();
            if (Files.exists(thumbnailFile)) {
                Files.delete(thumbnailFile);
            }
        } catch (IOException e) {
            log.warn("Failed to delete file {}: {}", safePath, e.getMessage());
        }

        return deleted;
    }

    private String getFileExtension(String fileName)
    {
        if (fileName == null || !fileName.contains(".")) return ".jpg";
        return fileName.substring(fileName.lastIndexOf("."));
    }

    private String stripExtension(String fileName)
    {
        if (fileName == null || !fileName.contains(".")) return fileName;
        return fileName.substring(0, fileName.lastIndexOf("."));
    }

    String normalizeDestinationDirectory(String destinationDirectory)
    {
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

    Path resolveStorageDirectory(String destinationDirectory)
    {
        Path storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        Path destinationRoot = destinationDirectory == null || destinationDirectory.isBlank()
                ? storageRoot
                : storageRoot.resolve(destinationDirectory).normalize();

        if (!destinationRoot.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Destination directory must stay within the configured storage path");
        }

        return destinationRoot;
    }

    private String normalizeRelativePath(Path path)
    {
        return path.toString().replace(File.separatorChar, '/');
    }

}
