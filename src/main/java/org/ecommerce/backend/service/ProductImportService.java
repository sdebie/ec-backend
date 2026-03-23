package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.dto.ProductUploadBatchProcessStatusDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.enums.ProductTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
import org.ecommerce.common.repository.ProductImportRepository;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductImportService {

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    EntityManager entityManager;

    @Inject
    ProductImportRepository productImportRepository;

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);

    /**
     * Creates and persists the batch record immediately (status=IMPORTING) so the
     * caller can return a batch ID to the client without waiting for CSV parsing.
     */
    @Transactional
    public ProductUploadBatchEntity createPendingBatch(String filename, StaffUserEntity admin) {
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.filename = filename;
        batch.productUploadStatusEn = ProductUploadStatusEn.IMPORTING;
        batch.uploadedBy = admin;
        batch.persist();
        return batch;
    }

    /**
     * Parses the CSV and stages all rows for an already-created batch.
     * Marks the batch PENDING when done.
     * Intended to be called from a background thread.
     */
    @Transactional
    public void handleCsvUploadForBatch(InputStream is, UUID batchId) throws IOException {
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        int rowCount = 0;
        int validationErrorCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is));
             CSVParser csvParser = new CSVParser(
                     reader,
                     CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setIgnoreHeaderCase(true)
                             .setTrim(true)
                             .build()
             )) {

            for (CSVRecord record : csvParser) {
                ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
                staged.batch = batch;

                List<String> validationErrors = new ArrayList<>();

                staged.sku = getValue(record, "sku", "SKU");
                staged.name = getValue(record, "name", "Name");
                staged.description = getValue(record, "description", "description");
                staged.categorySlug = getValue(record, "category_slug", "Category", "category_name");
                staged.shortDescription = getValue(record, "short_description", "short_description");
                staged.retailPrice = parseBigDecimal(record, validationErrors, "retail_price", "Retail Price");
                staged.retailSalePrice = parseBigDecimal(record, validationErrors, "retail_sale_price", "Retail Sale Price");
                staged.wholesalePrice = parseBigDecimal(record, validationErrors, "wholesale_price", "Wholesale Price");
                staged.wholesaleSalePrice = parseBigDecimal(record, validationErrors, "wholesale_sale_price", "Wholesale Sale Price");

                Integer stock = parseStockInteger(record, validationErrors);
                String brandSlug = getValue(record, "brand_slug", "brand_name", "Brand");
                String imagesValue = getValue(record, "images");
                String attributesJson = getValue(record, "attributes");

                staged.stock = stock;
                staged.brandSlug = brandSlug;
                staged.images = imagesValue;
                staged.attributes = attributesJson;

                validateAndDiff(staged, validationErrors, stock, brandSlug, imagesValue, attributesJson);

                validateImages(staged, validationErrors);

                applyValidationResults(staged, validationErrors);
                validationErrorCount += validationErrors.size();
                if (!validationErrors.isEmpty()) {
                    LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", record.getRecordNumber(), staged.sku, staged.validationErrors);
                }

                staged.persist();
                rowCount++;

                if (rowCount % 500 == 0) {
                    entityManager.flush();
                }
            }
        }

        entityManager.flush();
        batch.totalRows = rowCount;
        batch.validationErrorCount = validationErrorCount;
        batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        entityManager.flush();
    }

    @Transactional
    public void markBatchAsProcessing(UUID batchId) {
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        if (batch.productUploadStatusEn == ProductUploadStatusEn.PROCESSING) {
            throw new IllegalStateException("Batch is already processing");
        }

        long totalRows = ProductUploadStagedEntity.count("batch.id", batchId);
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSING;
        batch.totalRows = (int) totalRows;
        batch.processedRows = 0;
        batch.skippedRows = 0;
    }

    @Transactional
    public void markBatchAsProcessed(UUID batchId) {
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.PROCESSED;
    }

    @Transactional
    public void markBatchAsFailed(UUID batchId) {
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }
        batch.productUploadStatusEn = ProductUploadStatusEn.FAILED;
    }

    @Transactional
    public void processStagedRowsForBatch(UUID batchId) {

        LOG.debug("DEBUG:: Processing Batch: " + batchId);
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        List<ProductUploadStagedEntity> stagedRows = ProductUploadStagedEntity.list("batch.id = ?1 and processed = false", batchId);
        long processedCount = 0;
        long skippedCount = 0;

        for (ProductUploadStagedEntity staged : stagedRows) {
            if (staged.validationStatus == ProductImportValidationStatusEn.VALID) {
                applyValidStagedRow(staged);
                processedCount++;
            } else {
                skippedCount++;
            }

            staged.processed = true;
            LOG.debugf("DEBUG:: processed=%d skipped=%d", processedCount, skippedCount);

            if ((processedCount + skippedCount) % 500 == 0) {
                entityManager.flush();
            }
        }

        entityManager.flush();

        long totalRows = ProductUploadStagedEntity.count("batch.id", batchId);
        long totalProcessedRows = ProductUploadStagedEntity.count(
                "batch.id = ?1 and processed = true and validationStatus = ?2",
                batchId,
                ProductImportValidationStatusEn.VALID
        );
        long totalSkippedRows = ProductUploadStagedEntity.count(
                "batch.id = ?1 and processed = true and (validationStatus is null or validationStatus <> ?2)",
                batchId,
                ProductImportValidationStatusEn.VALID
        );
        batch.totalRows = (int) totalRows;
        batch.processedRows = (int) totalProcessedRows;
        batch.skippedRows = (int) totalSkippedRows;
    }

    private void applyValidStagedRow(ProductUploadStagedEntity staged) {
        CategoryEntity category = null;
        BrandEntity brand = null;

        if (!isBlank(staged.categorySlug)) {
            category = CategoryEntity.find("lower(slug) = ?1", staged.categorySlug.trim().toLowerCase(Locale.ROOT)).firstResult();
        }
        if (!isBlank(staged.brandSlug)) {
            brand = BrandEntity.find("lower(slug) = ?1", staged.brandSlug.trim().toLowerCase(Locale.ROOT)).firstResult();
        }

        ProductVariantEntity variant = ProductVariantEntity.find("sku", staged.sku).firstResult();
        ProductEntity product;

        if (variant != null) {
            product = variant.product;
        } else {
            product = null;
            if (!isBlank(staged.name)) {
                product = ProductEntity.find("lower(name) = ?1", staged.name.trim().toLowerCase(Locale.ROOT)).firstResult();
            }
            if (product == null) {
                product = new ProductEntity();
                product.name = staged.name;
                product.description = staged.description;
                product.shorDescription = staged.shortDescription;
                product.productType = ProductTypeEn.VARIABLE;
                product.persist();
            }

            variant = new ProductVariantEntity();
            variant.product = product;
            variant.sku = staged.sku;
            variant.persist();
        }

        product.name = staged.name.trim();
        product.description = staged.description;
        product.shorDescription = staged.shortDescription;

        if (category != null) {
            product.category = category;
        }
        if (brand != null) {
            product.brand = brand;
        }
        if (product.productType == null) {
            product.productType = ProductTypeEn.VARIABLE;
        }

        variant.stockQuantity = staged.stock != null ? staged.stock : 0;
        variant.attributesJson = trimToNull(staged.attributes);

        upsertVariantPrice(variant, PriceTypeEn.RETAIL_PRICE, staged.retailPrice);
        upsertVariantPrice(variant, PriceTypeEn.RETAIL_SALE_PRICE, staged.retailSalePrice);
        upsertVariantPrice(variant, PriceTypeEn.WHOLESALE_PRICE, staged.wholesalePrice);
        upsertVariantPrice(variant, PriceTypeEn.WHOLESALE_SALE_PRICE, staged.wholesaleSalePrice);

        upsertVariantImages(variant, staged.images);
    }

    private void upsertVariantImages(ProductVariantEntity variant, String stagedImages) {
        ProductImageEntity.delete("productVariant.id", variant.id);

        List<String> imageNames = splitImageNames(stagedImages);
        for (int i = 0; i < imageNames.size(); i++) {
            ProductImageEntity image = new ProductImageEntity();
            image.productVariant = variant;
            image.imageUrl = imageNames.get(i);
            image.sortOrder = i;
            image.isFeatured = i == 0;
            image.persist();
        }
    }

    private void upsertVariantPrice(ProductVariantEntity variant, PriceTypeEn priceType, BigDecimal priceValue) {
        if (variant == null || variant.id == null || priceType == null || priceValue == null) {
            return;
        }

        VariantPricesEntity price = VariantPricesEntity.findLatestByVariantAndType(variant.id, priceType);
        if (price == null) {
            price = new VariantPricesEntity();
            price.variant = variant;
            price.priceType = priceType;
            price.price = priceValue;
            price.persist();
            return;
        }

        price.price = priceValue;
        price.priceEndDate = null;
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductUploadBatchProcessStatusDto getBatchProcessStatus(UUID batchId) {
        ProductUploadBatchEntity batch = ProductUploadBatchEntity.findById(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch not found: " + batchId);
        }

        ProductUploadBatchProcessStatusDto status = new ProductUploadBatchProcessStatusDto();
        status.batchId = batch.id;
        status.status = batch.productUploadStatusEn != null ? batch.productUploadStatusEn.name() : null;
        status.totalRows = batch.totalRows;
        status.stagedRows = ProductUploadStagedEntity.count("batch.id", batchId);
        status.processedRows = batch.processedRows != null ? (long) batch.processedRows : 0L;
        status.skippedRows = batch.skippedRows != null ? (long) batch.skippedRows : 0L;
        status.validationErrorCount = batch.validationErrorCount;
        status.completed = batch.productUploadStatusEn != ProductUploadStatusEn.PROCESSING;
        return status;
    }

    private void validateAndDiff(
            ProductUploadStagedEntity staged,
            List<String> validationErrors,
            Integer stock,
            String brandSlug,
            String imagesValue,
            String attributesJson
    ) {
        CategoryEntity category = findExistingCategory(staged.categorySlug, validationErrors);
        BrandEntity brand = findExistingBrand(brandSlug, validationErrors);
        ProductEntity existingProduct = findExistingProduct(staged.name);
        ProductVariantEntity existingVariant = findExistingVariant(staged.sku, validationErrors);

        staged.isValidCategory = category != null;
        staged.isValidBrand = brand != null;

        staged.isNewProduct = existingProduct == null;
        staged.isNewVariant = existingVariant == null;

        //Variant Exist
        if (!staged.isNewVariant) {
            //New Product
            if (staged.isNewProduct) {
                validationErrors.add("SKU " + staged.sku + " already exists for product " + safeProductName(existingVariant));
            } else if (existingVariant.product != null && !Objects.equals(existingVariant.product.id, existingProduct.id)) {
                validationErrors.add("SKU " + staged.sku + " already belongs to another product");
            }
        }

        staged.hasChanges = determineHasChanges(
                staged,
                existingProduct,
                existingVariant,
                stock,
                imagesValue,
                attributesJson
        );
    }

    private void validateImages(ProductUploadStagedEntity staged, List<String> validationErrors) {
        if (isBlank(staged.images)) {
            return;
        }

        String[] images = staged.images.split(",");
        List<String> missing = new ArrayList<>();

        for(String fileName :images) {
            java.io.File file = new java.io.File(storagePath, fileName.trim());
            if (!file.exists()) {
                missing.add(fileName.trim());
            }
        }

        if(!missing.isEmpty()) {
            staged.imageErrors = "Missing Images: " + String.join(", ", missing);
            validationErrors.add("Image not foud: " + String.join(", ", missing));
        }
    }

    private CategoryEntity findExistingCategory(String categorySlug, List<String> validationErrors) {
        if (isBlank(categorySlug)) {
            validationErrors.add("category is required");
            return null;
        }

        CategoryEntity category = CategoryEntity.find("lower(slug) = ?1", categorySlug.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (category == null) {
            validationErrors.add("Unknown category: " + categorySlug.trim());
        }
        return category;
    }

    private BrandEntity findExistingBrand(String brandSlug, List<String> validationErrors) {
        if (isBlank(brandSlug)) {
            validationErrors.add("brand is required");
            return null;
        }

        BrandEntity brand = BrandEntity.find("lower(slug) = ?1", brandSlug.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (brand == null) {
            validationErrors.add("Unknown brand: " + brandSlug.trim());
        }
        return brand;
    }

    private ProductEntity findExistingProduct(String productName) {
        if (isBlank(productName)) {
            //validationErrors.add("name is required");
            return null;
        }

        return ProductEntity.find("lower(name) = ?1", productName.trim().toLowerCase(Locale.ROOT)).firstResult();
    }

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors) {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        return ProductVariantEntity.find("sku", sku.trim()).firstResult();
    }

    private boolean determineHasChanges(
            ProductUploadStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant,
            Integer stock,
            String imagesValue,
            String attributesJson
    ) {
        if (existingProduct == null || existingVariant == null) {
            return true;
        }

        boolean nameChanged = !Objects.equals(trimToNull(staged.name), trimToNull(existingProduct.name));
        boolean categoryChanged = !Objects.equals(trimToNull(staged.categorySlug), trimToNull(existingProduct.category != null ? existingProduct.category.slug : null));
        boolean brandChanged = !Objects.equals(trimToNull(staged.brandSlug), trimToNull(existingProduct.brand != null ? existingProduct.brand.slug : null));
        boolean stockChanged = !Objects.equals(stock, existingVariant.stockQuantity);
        boolean attributesChanged = !Objects.equals(trimToNull(attributesJson), trimToNull(existingVariant.attributesJson));
        boolean imagesChanged = !sameImageNames(imagesValue, existingVariant);
        boolean retailChanged = pricesDiffer(staged.retailPrice, findLatestPrice(existingVariant, PriceTypeEn.RETAIL_PRICE));
        boolean wholesaleChanged = pricesDiffer(staged.wholesalePrice, findLatestPrice(existingVariant, PriceTypeEn.WHOLESALE_PRICE));

        return nameChanged || categoryChanged || brandChanged || stockChanged || attributesChanged || imagesChanged || retailChanged || wholesaleChanged;
    }

    private BigDecimal findLatestPrice(ProductVariantEntity variant, PriceTypeEn priceType) {
        if (variant == null || variant.id == null) {
            return null;
        }
        VariantPricesEntity price = VariantPricesEntity.findLatestByVariantAndType(variant.id, priceType);
        return price != null ? price.price : null;
    }

    private boolean pricesDiffer(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return false;
        }
        if (left == null || right == null) {
            return true;
        }
        return left.compareTo(right) != 0;
    }

    private boolean sameImageNames(String stagedImages, ProductVariantEntity variant) {
        if (variant == null || variant.id == null) {
            return false;
        }

        List<String> existing = ProductImageEntity.list("productVariant.id", variant.id).stream()
                .map(ProductImageEntity.class::cast)
                .map(img -> extractFileName(img.imageUrl))
                .filter(name -> !isBlank(name))
                .toList();

        List<String> proposed = splitImageNames(stagedImages);
        return existing.equals(proposed);
    }

    private List<String> splitImageNames(String imagesValue) {
        if (isBlank(imagesValue)) {
            return List.of();
        }
        return Arrays.stream(imagesValue.split(","))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private String extractFileName(String imageUrl) {
        String normalized = trimToNull(imageUrl);
        if (normalized == null) {
            return null;
        }
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private String safeProductName(ProductVariantEntity variant) {
        if (variant == null || variant.product == null || isBlank(variant.product.name)) {
            return "<unknown>";
        }
        return variant.product.name;
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private BigDecimal parseBigDecimal(CSVRecord record, List<String> validationErrors, String... headers) {
        String value = getValue(record, headers);
        if (isBlank(value)) {
            return new BigDecimal(0);
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid decimal value for " + headers[0] + ": " + value);
            return null;
        }
    }

    private Integer parseStockInteger(CSVRecord record, List<String> validationErrors) {
        String value = getValue(record, "stock", "stock_quantity");
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid integer value for stock: " + value);
            return null;
        }
    }

    private String getValue(CSVRecord record, String... headers) {
        for (String header : headers) {
            if (record.isMapped(header)) {
                String value = record.get(header);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public List<ProductComparisonDto> getProductImportRows(UUID batchId) {
        List<ProductUploadStagedEntity> stagedList = ProductUploadStagedEntity.find("batch.id = ?1", batchId).list();

        return stagedList.stream().map(staged -> {
            ProductComparisonDto dto = new ProductComparisonDto();
            if (!staged.isNewProduct){
                ProductEntity existingProduct = ProductEntity.find("lower(name) = ?1", staged.name.trim().toLowerCase(Locale.ROOT)).firstResult();
                dto.currentName = existingProduct.name;
                dto.currentDescription = existingProduct.description;
                dto.currentShortDescription = existingProduct.shorDescription;
            }
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
            dto.proposedDescription = staged.description;
            dto.proposedShortDescription = staged.shortDescription;
            dto.categorySlug = staged.categorySlug;
            dto.brandSlug = staged.brandSlug;
            dto.proposedImages = staged.images;
            dto.proposedStock = staged.stock;
            dto.proposedAttributes = staged.attributes;
            dto.validationErrors = staged.validationErrors;
            dto.validationStatus = staged.validationStatus;
            dto.imageErrors = staged.imageErrors;

            dto.proposedName = staged.name;
            dto.proposedRetailPrice = staged.retailPrice;
            dto.proposedWholesalePrice = staged.wholesalePrice;
            dto.proposedRetailSalePrice = staged.retailSalePrice;
            dto.proposedWholesaleSalePrice = staged.wholesaleSalePrice;
            dto.isValidCategory = staged.isValidCategory;
            dto.isValidBrand = staged.isValidBrand;
            dto.isNewProduct = staged.isNewProduct;
            dto.isNewVariant = staged.isNewVariant;
            dto.hasChanges = Boolean.TRUE.equals(staged.hasChanges);

            ProductVariantEntity variant = ProductVariantEntity.find("sku", staged.sku).firstResult();
            if (variant != null) {
                dto.currentName = variant.product.name;
                dto.currentDescription = variant.product.description;
                dto.currentShortDescription = variant.product.shorDescription;
                BigDecimal retailPrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.RETAIL_PRICE);
                BigDecimal retailSalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.RETAIL_SALE_PRICE);
                BigDecimal wholesalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.WHOLESALE_PRICE);
                BigDecimal wholesaleSalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.WHOLESALE_SALE_PRICE);

                dto.currentRetailPrice = retailPrice;
                dto.currentWholesalePrice = wholesalePrice;
                dto.currentRetailSalePrice = retailSalePrice;
                dto.currentWholesaleSalePrice = wholesaleSalePrice;

                dto.currentStock = variant.stockQuantity;
                dto.currentAttributes = variant.attributesJson;
            }

            return dto;
        }).collect(Collectors.toList());
    }

    public List<ProductUploadBatchDto> getProductUploadBatches() {
        List<ProductUploadBatchEntity> batches = ProductUploadBatchEntity.listAll();
        return batches.stream().map(batch -> {
            ProductUploadBatchDto dto = new ProductUploadBatchDto();
            dto.id = batch.id;
            dto.filename = batch.filename;
            dto.status = batch.productUploadStatusEn.toString();
            dto.totalRows = batch.totalRows;
            dto.processedRows = batch.processedRows;
            dto.skippedRows = batch.skippedRows;
            dto.validationErrorCount = batch.validationErrorCount;
            dto.createdAt = batch.createdAt;
            dto.uploadedByUsername = batch.uploadedBy != null ? batch.uploadedBy.username : null;
            return dto;
        }).collect(Collectors.toList());
    }

    private void applyValidationResults(ProductUploadStagedEntity staged, List<String> validationErrors) {
        staged.validationStatus = validationErrors.isEmpty()
                ? ProductImportValidationStatusEn.VALID
                : ProductImportValidationStatusEn.INVALID;
        staged.validationErrors = validationErrors.isEmpty() ? null : String.join("; ", validationErrors);
    }

}
