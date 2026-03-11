package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;
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
    public String storagePath;

    private static final Logger LOG = Logger.getLogger(ProductImportService.class);

    @Transactional
    public ProductUploadBatchEntity handleCsvUpload(InputStream is, String filename, StaffUserEntity admin) throws IOException {
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.filename = filename;
        batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        batch.uploadedBy = admin;
        batch.persist();

        int rowCount = 0;

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
                staged.categorySlug = getValue(record, "category_slug", "Category", "category_name");
                staged.retailPrice = parseBigDecimal(record, validationErrors, "retail_price", "Retail Price");
                staged.retailSalePrice = parseBigDecimal(record, validationErrors, "retail_sale_price", "Retail Sale Price");
                staged.wholesalePrice = parseBigDecimal(record, validationErrors, "wholesale_price", "Wholesale Price");
                staged.wholesaleSalePrice = parseBigDecimal(record, validationErrors, "wholesale_sale_price", "Wholesale Sale Price");

                Integer stock = parseInteger(record, validationErrors, "stock", "stock_quantity");
                String brandSlug = getValue(record, "brand_slug", "brand_name", "Brand");
                String imagesValue = getValue(record, "images");
                String attributesJson = getValue(record, "attributes");

                staged.stock = stock;
                staged.brandSlug = brandSlug;
                staged.images = imagesValue;
                staged.attributes = attributesJson;

                validateAndDiff(staged, validationErrors, stock, brandSlug, imagesValue, attributesJson);

                validateImages(staged, validationErrors);

                staged.validationStatus = validationErrors.isEmpty() ? "VALID" : "INVALID";
                staged.validationErrors = validationErrors.isEmpty() ? null : String.join("; ", validationErrors);
                if (!validationErrors.isEmpty()) {
                    LOG.warnf("CSV import validation failed at row %d (sku=%s): %s", record.getRecordNumber(), staged.sku, staged.validationErrors);
                }

                staged.persist();
                rowCount++;
            }
        }

        batch.totalRows = rowCount;
        return batch;
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
        ProductEntity existingProduct = findExistingProduct(staged.name, validationErrors);
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

    private ProductEntity findExistingProduct(String productName, List<String> validationErrors) {
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
        boolean retailChanged = !pricesMatch(staged.retailPrice, findLatestPrice(existingVariant, PriceTypeEn.RETAIL_PRICE));
        boolean wholesaleChanged = !pricesMatch(staged.wholesalePrice, findLatestPrice(existingVariant, PriceTypeEn.WHOLESALE_PRICE));

        return nameChanged || categoryChanged || brandChanged || stockChanged || attributesChanged || imagesChanged || retailChanged || wholesaleChanged;
    }

    private BigDecimal findLatestPrice(ProductVariantEntity variant, PriceTypeEn priceType) {
        if (variant == null || variant.id == null) {
            return null;
        }
        VariantPricesEntity price = VariantPricesEntity.findLatestByVariantAndType(variant.id, priceType);
        return price != null ? price.price : null;
    }

    private boolean pricesMatch(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
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

    private Integer parseInteger(CSVRecord record, List<String> validationErrors, String... headers) {
        String value = getValue(record, headers);
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            validationErrors.add("Invalid integer value for " + headers[0] + ": " + value);
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
            }
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
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
            dto.createdAt = batch.createdAt;
            dto.uploadedByUsername = batch.uploadedBy != null ? batch.uploadedBy.username : null;
            return dto;
        }).collect(Collectors.toList());
    }

}
