package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.ecommerce.backend.utils.PriceUtils;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;
import org.ecommerce.common.entity.ProductUploadBatchEntity;
import org.ecommerce.common.entity.ProductUploadStagedEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductUploadStatusEn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.ecommerce.backend.utils.PriceUtils.getMinimumPrice;

@ApplicationScoped
public class ProductImportService {

    @Transactional
    public ProductUploadBatchEntity handleCsvUpload(InputStream is, String filename, StaffUserEntity admin) throws IOException {
        // 1. Create the Batch record
        ProductUploadBatchEntity batch = new ProductUploadBatchEntity();
        batch.filename = filename;
        batch.productUploadStatusEn = ProductUploadStatusEn.PENDING;
        batch.uploadedBy = admin;
        batch.persist();

        int rowCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is));
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim())) {

            for (CSVRecord record : csvParser) {
                ProductUploadStagedEntity staged = new ProductUploadStagedEntity();
                staged.batch = batch;
                staged.sku = record.get("SKU");
                staged.name = record.get("Name");
                staged.retailPrice = new BigDecimal(record.get("Retail Price"));
                staged.wholesalePrice = new BigDecimal(record.get("Wholesale Price"));
                staged.categoryName = record.get("Category");

                // 2. The "Senior Dev" Validation Logic
                validateAndDiff(staged);

                staged.persist();
                rowCount++;
            }
        }

        batch.totalRows = rowCount;
        return batch;
    }

    private void validateAndDiff(ProductUploadStagedEntity staged) {
        // Find existing variant by SKU to see if this is an update or a new product
        // Using Panache pattern: ProductVariantEntity.find("sku", staged.sku).firstResultOptional()
        Optional<ProductVariantEntity> existing = ProductVariantEntity.find("sku", staged.sku).firstResultOptional();

        if (existing.isEmpty()) {
            staged.isNewProduct = true;
            staged.hasChanges = true;
        } else {
            staged.isNewProduct = false;
            // For now, we flag changes if it exists, further refined logic can compare specific fields
            // like price or name from the associated Product entity if needed.
            staged.hasChanges = true;
        }
    }

    public List<ProductComparisonDto> getProductImportRows(UUID batchId) {
        List<ProductUploadStagedEntity> stagedList = ProductUploadStagedEntity.find("batch.id = ?1", batchId).list();

        return stagedList.stream().map(staged -> {
            ProductComparisonDto dto = new ProductComparisonDto();
            dto.stagedId = staged.id;
            dto.sku = staged.sku;
            dto.proposedName = staged.name;
            dto.proposedRetailPrice = staged.retailPrice;
            dto.proposedWholesalePrice = staged.wholesalePrice;
            dto.isNewProduct = staged.isNewProduct;

            // Fetch live data by SKU
            ProductVariantEntity variant = ProductVariantEntity.find("sku", staged.sku).firstResult();
            if (variant != null) {
                dto.currentName = variant.product.name;
                BigDecimal retailPrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.RETAIL_PRICE);
                BigDecimal retailSalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.RETAIL_SALE_PRICE);
                BigDecimal wholesalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.WHOLESALE_PRICE);
                BigDecimal wholesaleSalePrice = PriceUtils.getMinimumPrice(variant.product.id, PriceTypeEn.WHOLESALE_SALE_PRICE);

                dto.currentRetailPrice = retailPrice;
                dto.currentWholesalePrice = wholesalePrice;

                // Logic to highlight changes in the UI
                dto.hasChanges = !dto.proposedName.equals(dto.currentName) ||
                        dto.proposedRetailPrice.compareTo(dto.currentRetailPrice) != 0;
            } else {
                dto.isNewProduct = true;
                dto.hasChanges = true;
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