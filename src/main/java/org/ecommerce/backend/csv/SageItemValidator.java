package org.ecommerce.backend.csv;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;

import static org.ecommerce.common.util.CsvImportUtils.isBlank;

/**
 * Validates Sage item import staged rows.
 * Sage data is authoritative, so validation is focused on:
 * - SKU not blank
 * - SKU conflicts (duplicate SKU for different product)
 * - Existing product/variant detection (for diff)
 */
@ApplicationScoped
public class SageItemValidator {
    private static final Logger LOG = Logger.getLogger(SageItemValidator.class);

    // Error messages
    private static final String SKU_REQUIRED = "SKU is required";
    private static final String SKU_EXISTS_SAME_PRODUCT = "SKU %s already exists for product %s";
    private static final String SKU_EXISTS_OTHER_PRODUCT = "SKU %s already belongs to another product";

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    /**
     * Validates a Sage item staged row and detects changes relative to existing data.
     * Mutates the staged entity's flags (isNewProduct, isNewVariant, hasChanges, current* fields)
     * and appends validation errors to the provided list.
     *
     * @param staged the staged entity to validate (mutated in place)
     * @param validationErrors mutable list to which validation errors are appended
     */
    public void validateAndDiff(ProductImportStagedEntity staged, List<String> validationErrors) {
        // Validate SKU is present
        if (isBlank(staged.getSku())) {
            validationErrors.add(SKU_REQUIRED);
            return;
        }

        // Resolve references and set flags
        ProductEntity existingProduct = findExistingProduct(staged.getProductSlug(), staged.getName());
        ProductVariantEntity existingVariant = findExistingVariant(staged.getSku());

        staged.setIsNewProduct(existingProduct == null);
        staged.setIsNewVariant(existingVariant == null);

        // Validate SKU conflicts
        validateSkuConflicts(staged, existingProduct, existingVariant, validationErrors);

        // Detect changes
        staged.setHasChanges(determineHasChanges(staged, existingProduct, existingVariant));

        // Capture current values for comparison display
        captureCurrentValues(staged, existingProduct, existingVariant);
    }

    /**
     * Applies validation results to the staged entity — sets the validation status
     * and joins error messages.
     */
    public void applyValidationResults(ProductImportStagedEntity staged, List<String> validationErrors) {
        staged.setValidationStatus(validationErrors.isEmpty() ? ProductImportValidationStatusEn.VALID : ProductImportValidationStatusEn.INVALID);
        staged.setValidationErrors(validationErrors.isEmpty() ? null : String.join("; ", validationErrors));
    }

    // --- Extracted validation methods ---

    /**
     * Validates SKU conflicts: checks if SKU already exists and if so,
     * whether it belongs to the same product or a different one.
     */
    private void validateSkuConflicts(
            ProductImportStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant,
            List<String> validationErrors
    ) {
        if (staged.getIsNewVariant()) {
            return; // No existing variant, no conflict
        }

        if (staged.getIsNewProduct()) {
            validationErrors.add(String.format(SKU_EXISTS_SAME_PRODUCT, staged.getSku(), safeProductName(existingVariant)));
        } else if (existingVariant.getProduct() != null && !Objects.equals(existingVariant.getProduct().getId(), existingProduct.getId())) {
            validationErrors.add(String.format(SKU_EXISTS_OTHER_PRODUCT, staged.getSku()));
        }
    }

    /**
     * Captures current (live) values from existing product and variant for comparison display.
     */
    private void captureCurrentValues(
            ProductImportStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant
    ) {
        if (existingVariant != null) {
            staged.setCurrentStock(existingVariant.getStockQuantity());
        }

        if (existingProduct != null) {
            staged.setCurrentName(existingProduct.getName());
            staged.setCurrentDescription(existingProduct.getDescription());
        }
    }

    // --- Private helper methods ---

    private ProductEntity findExistingProduct(String productSlug, String productName) {
        // Try finding by slug first
        if (!isBlank(productSlug)) {
            ProductEntity bySlug = productRepository.findBySlugIgnoreCase(productSlug);
            if (bySlug != null) {
                return bySlug;
            }
        }

        // Fall back to finding by name
        if (!isBlank(productName)) {
            return productRepository.findByNameIgnoreCase(productName);
        }

        return null;
    }

    private ProductVariantEntity findExistingVariant(String sku) {
        if (isBlank(sku)) {
            return null;
        }
        return productVariantRepository.findBySku(sku);
    }

    private boolean determineHasChanges(
            ProductImportStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant
    ) {
        // New products/variants always have changes
        if (existingProduct == null || existingVariant == null) {
            return true;
        }

        // Check each field for changes
        return descriptionChanged(staged, existingProduct)
                || stockChanged(staged, existingVariant);
    }

    private boolean descriptionChanged(ProductImportStagedEntity staged, ProductEntity existing) {
        return !Objects.equals(staged.getDescription(), existing.getDescription());
    }

    private boolean stockChanged(ProductImportStagedEntity staged, ProductVariantEntity existing) {
        return !Objects.equals(staged.getStock(), existing.getStockQuantity());
    }

    private String safeProductName(ProductVariantEntity variant) {
        if (variant == null || variant.getProduct() == null) {
            return "<unknown>";
        }
        return variant.getProduct().getName();
    }
}
