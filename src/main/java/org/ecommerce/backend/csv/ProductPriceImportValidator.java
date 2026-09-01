package org.ecommerce.backend.csv;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.ProductPriceImportStagedEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.VariantPricesRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.ecommerce.common.util.CsvImportUtils.isBlank;

/**
 * Validates price-import staged rows: SKU existence, variant lookup,
 * price diff/comparison, and error accumulation.
 */
@ApplicationScoped
public class ProductPriceImportValidator
{

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    /**
     * Result of validating and diffing a single price import row.
     *
     * @param variant               the looked-up variant (null if SKU is blank or not found)
     * @param validationErrors      accumulated validation errors (includes any parse errors passed in)
     * @param hasChanges            whether the proposed prices differ from current prices
     * @param currentRetailPrice    the current retail price for the variant (null if variant not found)
     * @param currentWholesalePrice the current wholesale price for the variant (null if variant not found)
     */
    public record ValidationResult(ProductVariantEntity variant, List<String> validationErrors,
                                   boolean hasChanges, BigDecimal currentRetailPrice,
                                   BigDecimal currentWholesalePrice)
    {
    }

    /**
     * Validates a parsed price row and computes the diff against current retail/wholesale
     * prices. Accumulates any parse errors alongside its own SKU-required / variant-not-found
     * failures.
     *
     * @param sku            the SKU from the parsed row
     * @param retailPrice    the proposed retail price
     * @param wholesalePrice the proposed wholesale price
     * @param parseErrors    any validation errors accumulated during parsing
     * @return validation result containing the variant, errors, diff state, and current prices
     */
    public ValidationResult validateAndDiff(String sku, BigDecimal retailPrice, BigDecimal wholesalePrice, List<String> parseErrors)
    {
        List<String> validationErrors = new ArrayList<>(parseErrors);

        ProductVariantEntity existingVariant = findExistingVariant(sku, validationErrors);

        BigDecimal currentRetailPrice = findLatestPrice(existingVariant, PriceTypeEn.RETAIL_PRICE);
        BigDecimal currentWholesalePrice = findLatestPrice(existingVariant, PriceTypeEn.WHOLESALE_PRICE);

        boolean retailChanged = pricesDiffer(retailPrice, currentRetailPrice);
        boolean wholesaleChanged = pricesDiffer(wholesalePrice, currentWholesalePrice);
        boolean hasChanges = retailChanged || wholesaleChanged;

        return new ValidationResult(existingVariant, validationErrors, hasChanges, currentRetailPrice, currentWholesalePrice);
    }

    /**
     * Applies validation results to the staged entity: sets the validation status
     * and joins error messages.
     *
     * @param staged           the staged entity to update
     * @param validationErrors the accumulated errors
     */
    public void applyValidationResults(ProductPriceImportStagedEntity staged, List<String> validationErrors)
    {
        staged.setValidationStatus(validationErrors.isEmpty() ? ProductImportValidationStatusEn.VALID : ProductImportValidationStatusEn.INVALID);
        staged.setValidationErrors(validationErrors.isEmpty() ? null : String.join("; ", validationErrors));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers — identical to the original service methods
    // ──────────────────────────────────────────────────────────────────────────

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors)
    {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        ProductVariantEntity variant = productVariantRepository.findBySku(sku);
        if (variant == null) {
            validationErrors.add("variant with sku '" + sku + "' not found");
        }
        return variant;
    }

    private boolean pricesDiffer(BigDecimal left, BigDecimal right)
    {
        if (left == null && right == null) {
            return false;
        }
        if (left == null || right == null) {
            return true;
        }
        return left.compareTo(right) != 0;
    }

    private BigDecimal findLatestPrice(ProductVariantEntity variant, PriceTypeEn priceType)
    {
        if (variant == null || variant.getId() == null) {
            return null;
        }
        VariantPricesEntity price = variantPricesRepository.findLatestByVariantAndType(variant.getId(), priceType);
        return price != null ? price.getPrice() : null;
    }
}
