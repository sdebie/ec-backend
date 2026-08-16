package org.ecommerce.backend.csv;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.ProductImportValidationStatusEn;
import org.ecommerce.common.repository.*;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.ecommerce.common.util.CsvImportUtils.isBlank;

/**
 * Validates product import staged rows against business rules and detects
 * changes (diff) relative to existing data.
 * <p>
 * Extracted from {@code ProductImportService} to separate validation concerns
 * from parsing and orchestration.
 */
@ApplicationScoped
public class ProductImportValidator
{

    private static final Logger LOG = Logger.getLogger(ProductImportValidator.class);

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    BrandRepository brandRepository;

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    /**
     * Validates a staged row, checks for SKU conflicts, resolves categories/brand,
     * and determines whether the row represents changes relative to existing data.
     * Mutates the staged entity's flags (isValidCategory, isValidBrand, isNewProduct,
     * isNewVariant, hasChanges, current* fields) and appends any validation errors
     * to the provided list.
     *
     * @param staged           the staged entity to validate (mutated in place)
     * @param validationErrors mutable list to which validation errors are appended
     * @param stock            parsed stock value (may be null if unparseable)
     * @param brandSlug        the brand slug from the CSV row
     * @param imagesValue      comma-separated image filenames from the CSV row
     * @param attributesJson   the attributes JSON string from the CSV row
     */
    public void validateAndDiff(
            ProductUploadStagedEntity staged,
            List<String> validationErrors,
            Integer stock,
            String brandSlug,
            String imagesValue,
            String attributesJson
    )
    {
        List<String> categorySlugs = splitCategorySlugs(staged.getCategorySlug());
        List<CategoryEntity> categories = findExistingCategories(categorySlugs, validationErrors);
        BrandEntity brand = findExistingBrand(brandSlug, validationErrors);
        ProductEntity existingProduct = findExistingProduct(staged.getProductSlug(), staged.getName());
        ProductVariantEntity existingVariant = findExistingVariant(staged.getSku(), validationErrors);

        staged.setIsValidCategory(!categorySlugs.isEmpty() && categories.size() == categorySlugs.size());
        staged.setIsValidBrand(brand != null);

        staged.setIsNewProduct(existingProduct == null);
        staged.setIsNewVariant(existingVariant == null);

        //Variant Exist
        if (!staged.getIsNewVariant()) {
            //New Product
            if (staged.getIsNewProduct()) {
                validationErrors.add("SKU " + staged.getSku() + " already exists for product " + safeProductName(existingVariant));
            } else if (existingVariant.getProduct() != null && !Objects.equals(existingVariant.getProduct().getId(), existingProduct.getId())) {
                validationErrors.add("SKU " + staged.getSku() + " already belongs to another product");
            }
        }

        staged.setHasChanges(determineHasChanges(
                staged,
                existingProduct,
                existingVariant,
                stock,
                imagesValue,
                attributesJson
        ));

        // Capture current (live) values for comparison display
        if (existingVariant != null) {
            staged.setCurrentStock(existingVariant.getStockQuantity());
            staged.setCurrentAttributes(existingVariant.getAttributesJson());
            List<String> existingImageNames = productImageRepository.findByVariantId(existingVariant.getId()).stream()
                    .map(img -> extractFileName(img.getImageUrl()))
                    .filter(name -> !isBlank(name))
                    .toList();
            staged.setCurrentImages(existingImageNames.isEmpty() ? null : String.join(",", existingImageNames));
        }
        if (existingProduct != null) {
            staged.setCurrentName(existingProduct.getName());
            staged.setCurrentDescription(existingProduct.getDescription());
            staged.setCurrentShortDescription(existingProduct.getShortDescription()); // note: typo in ProductEntity field name
        }
    }

    /**
     * Validates that images referenced by the staged row actually exist on disk.
     *
     * @param staged           the staged entity (imageErrors field is set if missing images found)
     * @param validationErrors mutable list to which validation errors are appended
     */
    public void validateImages(ProductUploadStagedEntity staged, List<String> validationErrors)
    {
        if (isBlank(staged.getImages())) {
            return;
        }

        Path storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();

        for (String imagePath : splitImageNames(staged.getImages())) {
            Path file = storageRoot.resolve(imagePath).normalize();
            if (!file.startsWith(storageRoot) || !file.toFile().isFile()) {
                LOG.warnf("Image not found or outside storage: %s", imagePath);
                missing.add(imagePath);
            }
        }

        if (!missing.isEmpty()) {
            staged.setImageErrors("Missing Images: " + String.join(", ", missing));
            validationErrors.add("Image not found: " + String.join(", ", missing));
        }
    }

    /**
     * Applies validation results to the staged entity — sets the validation status
     * and joins error messages.
     *
     * @param staged           the staged entity to update
     * @param validationErrors the list of accumulated validation errors
     */
    public void applyValidationResults(ProductUploadStagedEntity staged, List<String> validationErrors)
    {
        staged.setValidationStatus(validationErrors.isEmpty() ? ProductImportValidationStatusEn.VALID : ProductImportValidationStatusEn.INVALID);
        staged.setValidationErrors(validationErrors.isEmpty() ? null : String.join("; ", validationErrors));
    }

    // --- Private helper methods ---

    private List<CategoryEntity> findExistingCategories(List<String> categorySlugs, List<String> validationErrors)
    {
        if (categorySlugs.isEmpty()) {
            validationErrors.add("category is required");
            return List.of();
        }

        List<CategoryEntity> categories = new ArrayList<>();
        for (String categorySlug : categorySlugs) {
            CategoryEntity category = categoryRepository.findBySlugIgnoreCase(categorySlug);
            if (category == null) {
                validationErrors.add("Unknown category: " + categorySlug);
                continue;
            }
            categories.add(category);
        }
        return categories;
    }

    private BrandEntity findExistingBrand(String brandSlug, List<String> validationErrors)
    {
        if (isBlank(brandSlug)) {
            validationErrors.add("brand is required");
            return null;
        }

        BrandEntity brand = brandRepository.findBySlugIgnoreCase(brandSlug);
        if (brand == null) {
            validationErrors.add("Unknown brand: " + brandSlug.trim());
            return null;
        }
        return brand;
    }

    private ProductEntity findExistingProduct(String productSlug, String productName)
    {
        String normalizedSlug = normalizeSlug(productSlug);
        if (normalizedSlug != null) {
            ProductEntity slugMatch = productRepository.findBySlugIgnoreCase(normalizedSlug);
            if (slugMatch != null) {
                return slugMatch;
            }
        }

        if (isBlank(productName)) {
            return null;
        }

        return productRepository.findByNameIgnoreCase(productName);
    }

    private ProductVariantEntity findExistingVariant(String sku, List<String> validationErrors)
    {
        if (isBlank(sku)) {
            validationErrors.add("sku is required");
            return null;
        }

        return productVariantRepository.findBySku(sku);
    }

    private boolean determineHasChanges(
            ProductUploadStagedEntity staged,
            ProductEntity existingProduct,
            ProductVariantEntity existingVariant,
            Integer stock,
            String imagesValue,
            String attributesJson
    )
    {
        if (existingProduct == null || existingVariant == null) {
            return true;
        }

        boolean nameChanged = !Objects.equals(trimToNull(staged.getName()), trimToNull(existingProduct.getName()));
        boolean productSlugChanged = !Objects.equals(normalizeSlug(staged.getProductSlug()), normalizeSlug(existingProduct.getSlug()));
        boolean categoryChanged = !categorySlugSet(staged.getCategorySlug()).equals(categorySlugSet(existingProduct));
        boolean brandChanged = !Objects.equals(trimToNull(staged.getBrandSlug()), trimToNull(existingProduct.getBrand() != null ? existingProduct.getBrand().getSlug() : null));
        boolean stockChanged = !Objects.equals(stock, existingVariant.getStockQuantity());
        boolean attributesChanged = !Objects.equals(trimToNull(attributesJson), trimToNull(existingVariant.getAttributesJson()));
        boolean imagesChanged = !sameImageNames(imagesValue, existingVariant);

        return nameChanged || productSlugChanged || categoryChanged || brandChanged || stockChanged || attributesChanged || imagesChanged;
    }

    private boolean sameImageNames(String stagedImages, ProductVariantEntity variant)
    {
        if (variant == null || variant.getId() == null) {
            return false;
        }

        List<String> existing = productImageRepository.findByVariantId(variant.getId()).stream()
                .map(img -> extractFileName(img.getImageUrl()))
                .filter(name -> !isBlank(name))
                .toList();

        List<String> proposed = splitImageNames(stagedImages);
        return existing.equals(proposed);
    }

    private List<String> splitImageNames(String imagesValue)
    {
        if (isBlank(imagesValue)) {
            return List.of();
        }
        return Arrays.stream(imagesValue.split(","))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private String extractFileName(String imageUrl)
    {
        String normalized = trimToNull(imageUrl);
        if (normalized == null) {
            return null;
        }
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
    }

    private String safeProductName(ProductVariantEntity variant)
    {
        if (variant == null || variant.getProduct() == null || isBlank(variant.getProduct().getName())) {
            return "<unknown>";
        }
        return variant.getProduct().getName();
    }

    private String trimToNull(String value)
    {
        return isBlank(value) ? null : value.trim();
    }

    private String normalizeSlug(String value)
    {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> splitCategorySlugs(String categorySlugs)
    {
        if (isBlank(categorySlugs)) {
            return List.of();
        }

        return Arrays.stream(categorySlugs.split(","))
                .map(this::normalizeSlug)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Set<String> categorySlugSet(String categorySlugs)
    {
        return new LinkedHashSet<>(splitCategorySlugs(categorySlugs));
    }

    private Set<String> categorySlugSet(ProductEntity product)
    {
        if (product == null || product.getCategories() == null || product.getCategories().isEmpty()) {
            return Set.of();
        }

        return product.getCategories().stream()
                .map(category -> normalizeSlug(category.getSlug()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
