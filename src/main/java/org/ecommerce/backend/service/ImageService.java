package org.ecommerce.backend.service;

import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.ecommerce.common.enums.ImageTypeEn;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@ApplicationScoped
public class ImageService
{
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
        String newFileName = UUID.randomUUID().toString() + extension;

        // 3. Move the uploaded temp file to your storage path
        Path targetPath = root.resolve(newFileName);
        Files.copy(file.filePath(), targetPath);

        // 4. If image type is PRODUCT and entityId is provided, create ProductImageEntity
        if (imageType == ImageTypeEn.PRODUCT && entityId != null) {
            System.out.println("Creating ProductImageEntity for product: " + entityId + " URL:" + newFileName);
            createProductImage(newFileName, entityId);
        }

        // 5. Return the filename (or relative path) to store in the DB
        return newFileName;
    }

    /**
     * Create a ProductImageEntity record in the database for the uploaded image.
     * The image will have the highest sort order.
     */
    private void createProductImage(String imageUrl, UUID productId)
    {
        ProductEntity product = entityManager.find(ProductEntity.class, productId);
        if (product == null) {
            throw new IllegalArgumentException("Product not found with id: " + productId);
        }

        // Find the max sort order to add as next in sequence
        Integer maxSortOrder = entityManager
                .createQuery("SELECT COALESCE(MAX(pi.sortOrder), 0) FROM ProductImageEntity pi WHERE pi.product.id = :productId", Integer.class)
                .setParameter("productId", productId)
                .getSingleResult();

        ProductImageEntity productImage = new ProductImageEntity();
        productImage.product = product;
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

//ernestuduje@gmail.com +2349023371003