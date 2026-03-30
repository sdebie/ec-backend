package org.ecommerce.backend.service;

import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImageService
{
    @ConfigProperty(name = "storage.path")
    public String storagePath;

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
        Path thumbDirectory = Paths.get(storagePath, "thumbnails");

        // 1. Ensure the directory exists (extra safety)
        Path root = Paths.get(storagePath);

        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        // 2. Generate a unique filename using UUID
        String extension = getFileExtension(file.fileName());
        String newFileName = UUID.randomUUID() + extension;
        Path thumbPath = thumbDirectory.resolve(newFileName);

        // 3. Move the uploaded temp file to your storage path
        Path targetPath = root.resolve(newFileName);
        Files.copy(file.filePath(), targetPath);

        // 4. If image type is PRODUCT and entityId is provided, create ProductImageEntity
        if (imageType == ImageTypeEn.PRODUCT && entityId != null) {
            System.out.println("Creating ProductImageEntity for product: " + entityId + " URL:" + newFileName);
            createProductImage(newFileName, entityId);
        }

        //4.1 Create thumbnail
        net.coobird.thumbnailator.Thumbnails.of(targetPath.toFile())
                .size(150, 150)
                .outputQuality(0.8)
                .toFile(thumbPath.toFile());

        // 5. Return the filename (or relative path) to store in the DB
        return newFileName;
    }

    public Map<String, Integer> bulkUploadImages(List<FileUpload> uploads) {
        int uploadedCount = 0;
        int skippedCount = 0;
        Path thumbDirectory = Paths.get(storagePath, "thumbnails");

        for (FileUpload file : uploads) {
            try {
                Path fullPath = Paths.get(file.fileName());
                String justTheFileName = fullPath.getFileName().toString();
                Path targetPath = Paths.get(storagePath, justTheFileName);
                Path thumbPath = thumbDirectory.resolve(justTheFileName);

                // 2. ONLY save if it doesn't exist
                if (Files.notExists(targetPath)) {
                    Files.copy(file.filePath(), targetPath);
                    Files.createDirectories(thumbDirectory);

                    net.coobird.thumbnailator.Thumbnails.of(targetPath.toFile())
                            .size(150, 150)
                            .outputQuality(0.8)
                            .toFile(thumbPath.toFile());
                    uploadedCount++;
                } else {
                    skippedCount++;
                }
            } catch (IOException e) {
                // Log error for specific file but continue the loop
                System.err.println("DEBUG::Error saving " + e.getMessage());
                System.err.println("Error saving " + file.fileName());
            }
        }

        return Map.of(
                "uploaded", uploadedCount,
                "skipped", skippedCount
        );
    }

    public List<String> listImages() {
        File folder = new File(storagePath);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null) return Collections.emptyList();

        return Arrays.stream(listOfFiles)
                .filter(f -> f.isFile() && f.getName().matches(".*\\.(jpg|jpeg|png|webp)$"))
                .map(File::getName)
                .collect(Collectors.toList());
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

}