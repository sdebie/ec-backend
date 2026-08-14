package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.assembler.ProductListItemAssembler;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.CatalogueSortEn;
import org.ecommerce.common.enums.PriceBasisEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class ProductService
{
    @Inject
    ProductRepository productRepository;

    @Inject
    ProductListItemAssembler productListItemAssembler;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    ProductImageRepository productImageRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    BrandRepository brandRepository;

    @Inject
    VariantPricesRepository variantPricesRepository;

    @Inject
    ProductMapper productMapper;

    @Inject
    ProductWriteValidator productWriteValidator;

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getShoppingProducts(PageRequest pageRequest, FilterRequest filterRequest, boolean onSale, CatalogueSortEn sortBy, PriceBasisEn priceBasis, Boolean inStockOnly)
    {
        CatalogueSortEn effectiveSort = sortBy != null ? sortBy : CatalogueSortEn.NAME_ASC;
        PriceBasisEn effectiveBasis = priceBasis != null ? priceBasis : PriceBasisEn.RETAIL;
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, false);
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(productRepository.findShoppingProductEntities(pageRequest, effectiveFilterRequest, onSale, effectiveSort, effectiveBasis, inStockOnly), now, false);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSale(PageRequest pageRequest, boolean ignoreStatus)
    {
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(productRepository.findOnSaleProductEntities(pageRequest, ignoreStatus), now, ignoreStatus);
    }

    /**
     * Returns a paginated list of products for the admin product list table.
     * Supports optional filtering by status, categoryId, brandId, and search (name/SKU/barcode ILIKE).
     * For each product, resolves: primary variant SKU, thumbnail URL, lowest active retail price,
     * aggregated stock count, and derived stock level.
     */
    @Transactional(value = TxType.SUPPORTS)
    public PageResponse<AdminProductListItemDto> getAdminProductList(int pageIndex, int pageSize, String status, String categoryId, String brandId, String search)
    {
        LocalDateTime now = LocalDateTime.now();
        PageResponse<ProductEntity> page = productRepository.findAdminProductPage(pageIndex, pageSize, status, categoryId, brandId, search);

        List<AdminProductListItemDto> content = productListItemAssembler.buildAdminListItems(page.getContent(), now);

        return new PageResponse<>(content, page.getTotalElements(), page.getTotalPages(), page.getPageIndex(), page.getPageSize());
    }

    @Transactional(value = TxType.SUPPORTS)
    public AdminProductStatsDto getProductStats()
    {
        AdminProductStatsDto stats = new AdminProductStatsDto();
        stats.setTotal(productRepository.count());
        stats.setActive(productRepository.count("status", ProductStatusEn.ACTIVE));
        stats.setPending(productRepository.count("status", ProductStatusEn.PENDING));
        stats.setDisabled(productRepository.count("status", ProductStatusEn.DISABLED));
        return stats;
    }

    @Transactional(value = TxType.SUPPORTS)
    public long productCount(FilterRequest filterRequest)
    {
        return productRepository.count(applyActiveProductStatusFilter(filterRequest, false));
    }

    @Transactional(value = TxType.SUPPORTS)
    public long countShoppingProducts(FilterRequest filterRequest, boolean onSale, Boolean inStockOnly)
    {
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, false);
        return productRepository.countShoppingProducts(effectiveFilterRequest, onSale, inStockOnly);
    }

    private FilterRequest applyActiveProductStatusFilter(FilterRequest filterRequest, boolean ignoreStatus)
    {
        FilterRequest effectiveFilterRequest = copyFilterRequest(filterRequest);
        if (ignoreStatus) {
            return effectiveFilterRequest;
        }

        List<Filter> filters = mutableFilters(effectiveFilterRequest);
        filters.add(new Filter("status", FilterOperator.EQUALS, ProductStatusEn.ACTIVE.name()));
        effectiveFilterRequest.setFilters(filters);
        return effectiveFilterRequest;
    }

    private FilterRequest copyFilterRequest(FilterRequest filterRequest)
    {
        FilterRequest copy = new FilterRequest();
        if (filterRequest == null) {
            return copy;
        }

        copy.setSort(filterRequest.getSort());
        copy.setFilterGroups(filterRequest.getFilterGroups());
        copy.setFilters(filterRequest.getFilters() != null ? new ArrayList<>(filterRequest.getFilters()) : new ArrayList<>());
        return copy;
    }

    private List<Filter> mutableFilters(FilterRequest filterRequest)
    {
        return filterRequest.getFilters() != null ? new ArrayList<>(filterRequest.getFilters()) : new ArrayList<>();
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getTopBestSellers()
    {
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(productRepository.findTopBestSellerEntities(), now, true);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> getVariantsByIds(List<String> ids)
    {
        List<UUID> uuidIds = ids
                .stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());

        return productMapper.mapVariantEntitiesToDtos(productVariantRepository.findByIdsWithProduct(uuidIds));
    }

    @Transactional(value = TxType.SUPPORTS)
    public ProductInformationDto getProductInformationDto(String productId)
    {
        UUID pid = UUID.fromString(productId);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }

        // Active-only read: exclude DISABLED variants so soft-deleted variants are absent
        return productMapper.mapToProductInformationDto(product, productVariantRepository.findActiveVariantsForProductId(pid));
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(ProductInformationDto input)
    {
        // Validate the complete aggregate before any persistence
        productWriteValidator.validateForCreate(input);

        // Create new product entity
        ProductEntity product = new ProductEntity();
        productMapper.applyCreatableFields(input.getProduct(), product);

        // Link categories if provided
        if (input.getProduct().getCategories() != null && !input.getProduct().getCategories().isEmpty()) {
            for (CategoryDto categoryDto : input.getProduct().getCategories()) {
                if (categoryDto.getId() != null) {
                    UUID categoryId = categoryDto.getId();
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.getCategories().add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.getProduct().getCategory() != null && input.getProduct().getCategory().getId() != null) {
            // Backward compatibility: handle single category
            UUID categoryId = input.getProduct().getCategory().getId();
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.getCategories().add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Link brand if provided
        if (input.getProduct().getBrand() != null && input.getProduct().getBrand().getId() != null) {
            UUID brandId = input.getProduct().getBrand().getId();
            product.setBrand(brandRepository.findById(brandId));
            if (product.getBrand() != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save product
        product.persist();
        log.info("Created new product with ID: {}", product.getId());

        // Persist variants and their prices
        if (input.getVariants() != null && !input.getVariants().isEmpty()) {
            persistVariantsWithPrices(product, input.getVariants());
        }

        // Persist images: extract manifest from payload variant index 0
        List<ProductImageDto> imageManifest = extractImageManifest(input);
        updateProductImages(product.getId(), imageManifest);

        // Return the full aggregate via the active-only read path
        return productMapper.mapToProductInformationDto(product, productVariantRepository.findActiveVariantsForProductId(product.getId()));
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(String productId, ProductInformationDto input)
    {
        UUID pid = UUID.fromString(productId);

        // Validate the complete aggregate before any persistence
        productWriteValidator.validateForUpdate(pid, input);

        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);

        if (product == null) {
            log.error("Product not found with ID: {}", productId);
            throw new IllegalArgumentException("Product not found");
        }

        // Update product information (patch: only non-blank scalar fields)
        productMapper.applyEditableFields(input.getProduct(), product);

        // Update categories if provided
        if (input.getProduct().getCategories() != null && !input.getProduct().getCategories().isEmpty()) {
            product.getCategories().clear();
            for (CategoryDto categoryDto : input.getProduct().getCategories()) {
                if (categoryDto.getId() != null) {
                    UUID categoryId = categoryDto.getId();
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.getCategories().add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.getProduct().getCategory() != null && input.getProduct().getCategory().getId() != null) {
            // Backward compatibility: handle single category
            product.getCategories().clear();
            UUID categoryId = input.getProduct().getCategory().getId();
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.getCategories().add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Update brand if provided
        if (input.getProduct().getBrand() != null && input.getProduct().getBrand().getId() != null) {
            UUID brandId = input.getProduct().getBrand().getId();
            product.setBrand(brandRepository.findById(brandId));
            if (product.getBrand() != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save updated product
        product.persist();
        log.info("Updated product with ID: {}", product.getId());

        // Handle product variants updates
        if (input.getVariants() != null && !input.getVariants().isEmpty()) {
            updateProductVariants(pid, input.getVariants());
        }

        // Persist images: extract manifest from payload variant index 0 (after variant upsert)
        List<ProductImageDto> imageManifest = extractImageManifest(input);
        updateProductImages(pid, imageManifest);

        // Active-only read: exclude DISABLED variants from the returned aggregate
        return productMapper.mapToProductInformationDto(product, productVariantRepository.findActiveVariantsForProductId(pid));
    }

    /**
     * Extracts the product-level image manifest from the payload.
     * By convention, the frontend carries the manifest on variant index 0's images[].
     * This is a transport convention only — it does NOT define persistence ownership.
     */
    private List<ProductImageDto> extractImageManifest(ProductInformationDto input)
    {
        if (input.getVariants() == null || input.getVariants().isEmpty()) {
            return List.of();
        }
        ProductVariantDto firstVariant = input.getVariants().get(0);
        if (firstVariant.getImages() == null || firstVariant.getImages().isEmpty()) {
            return List.of();
        }
        return firstVariant.getImages();
    }

    /**
     * Reconciles the product-wide image manifest against existing product image associations.
     * <p>
     * The manifest is a single ordered list of images that the product editor carries on
     * payload variant index 0 (transport convention only). After all variant upserts, this
     * method determines the "owner variant" — the ACTIVE variant with the lowest UUID
     * (sorted by UUID.toString()) — and persists the manifest on it.
     * <p>
     * Steps:
     * 1. Determine the owner variant (active, lowest UUID).
     * 2. Load all existing ProductImageEntity rows for this product (across all variants).
     * 3. Reconcile: add new images, remove images not in the manifest, update sortOrder/isFeatured.
     * 4. Normalise: move any existing images on a non-owner variant to the owner variant.
     */
    private void updateProductImages(UUID productId, List<ProductImageDto> manifest)
    {
        log.info("Updating images for product ID: {}", productId);

        if (manifest == null) {
            manifest = List.of();
        }

        // 1. Determine the owner variant: active variant with the lowest UUID (string sort)
        List<ProductVariantEntity> allVariants = productVariantRepository.findByVariantsForProductId(productId);
        ProductVariantEntity ownerVariant = allVariants.stream()
                .filter(v -> v.getStatus() == ProductStatusEn.ACTIVE)
                .min(Comparator.comparing(a -> a.getId().toString()))
                .orElse(null);

        if (ownerVariant == null) {
            // No active variant — cannot persist images; remove all existing
            List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);
            for (ProductImageEntity image : existingImages) {
                if (image.getProductVariant() != null && image.getProductVariant().getImages() != null) {
                    image.getProductVariant().getImages().remove(image);
                }
                image.delete();
            }
            log.warn("No active variant found for product {}; removed all image associations", productId);
            return;
        }

        // 2. Load all existing images for this product (across all variants)
        List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);

        // Build a lookup of existing images by id for reconciliation
        Map<UUID, ProductImageEntity> existingById = new LinkedHashMap<>();
        for (ProductImageEntity img : existingImages) {
            existingById.put(img.getId(), img);
        }

        // 3. Reconcile: track which existing image ids are still in the manifest
        Set<UUID> manifestImageIds = new LinkedHashSet<>();

        for (int i = 0; i < manifest.size(); i++) {
            ProductImageDto imgDto = manifest.get(i);

            if (imgDto.getId() != null && !imgDto.getId().isBlank()) {
                // Existing image — update sortOrder, isFeatured, and normalise to owner
                UUID imageId = UUID.fromString(imgDto.getId());
                manifestImageIds.add(imageId);

                ProductImageEntity existing = existingById.get(imageId);
                if (existing != null) {
                    existing.setSortOrder(i);
                    existing.setIsFeatured(imgDto.isFeatured());
                    existing.setAltText(imgDto.getAltText());
                    // Normalise: move to owner variant if not already there
                    if (!ownerVariant.getId().equals(existing.getProductVariant().getId())) {
                        existing.setProductVariant(ownerVariant);
                    }
                    existing.persist();
                }
            } else {
                // New image — create entity on the owner variant
                ProductImageEntity newImage = new ProductImageEntity();
                newImage.setProductVariant(ownerVariant);
                newImage.setImageUrl(imgDto.getImageUrl());
                newImage.setSortOrder(i);
                newImage.setIsFeatured(imgDto.isFeatured());
                newImage.setAltText(imgDto.getAltText());
                newImage.persist();
                log.info("Created new image association for product {}: {}", productId, imgDto.getImageUrl());
            }
        }

        // 4. Remove images not in the manifest
        for (ProductImageEntity existing : existingImages) {
            if (!manifestImageIds.contains(existing.getId())) {
                // Remove from variant's managed collection to prevent orphanRemoval conflicts
                if (existing.getProductVariant() != null && existing.getProductVariant().getImages() != null) {
                    existing.getProductVariant().getImages().remove(existing);
                }
                existing.delete();
                log.info("Removed image association {} from product {}", existing.getId(), productId);
            }
        }
    }

    /**
     * Updates product variants and their prices, then removes variants absent from
     * the payload per the Deletion Policy.
     */
    private void updateProductVariants(UUID productId, List<ProductVariantDto> newVariants)
    {
        log.info("Updating variants for product ID: {}", productId);

        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(productId);
        if (product == null) {
            throw new IllegalArgumentException("Product not found for variant update: " + productId);
        }

        // Load existing variants BEFORE upserting so we can detect removals
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(productId);

        // Persist (create or upsert) each variant and its prices via the shared helper
        persistVariantsWithPrices(product, newVariants);

        // Determine which variant ids are present in the update payload
        Set<UUID> payloadVariantIds = newVariants
                .stream()
                .filter(v -> v.getId() != null && !v.getId().isBlank())
                .map(v -> UUID.fromString(v.getId()))
                .collect(Collectors.toSet());

        // Also include variants that were matched by SKU (newly created variants won't be in existing list)
        Set<String> payloadSkus = newVariants
                .stream()
                .filter(v -> v.getSku() != null)
                .map(v -> v.getSku().trim())
                .collect(Collectors.toSet());

        // Remove variants absent from the payload per the Deletion Policy
        for (ProductVariantEntity existing : existingVariants) {
            boolean inPayloadById = payloadVariantIds.contains(existing.getId());
            boolean inPayloadBySku = payloadSkus.contains(existing.getSku());

            if (!inPayloadById && !inPayloadBySku) {
                // This variant was removed from the editor — apply Deletion Policy
                boolean isDraft = product.getStatus() == ProductStatusEn.PENDING;
                boolean isOrderReferenced = productVariantRepository.isReferencedByOrders(existing.getId());

                if (isDraft && !isOrderReferenced) {
                    // Hard-delete: product is draft and variant is not order-referenced
                    log.info("Hard-deleting variant {} (SKU: {}) — draft product, no order references", existing.getId(), existing.getSku());
                    existing.delete();
                } else {
                    // Soft-delete: set status to DISABLED
                    log.info("Soft-deleting variant {} (SKU: {}) — setting status to DISABLED (draft={}, orderRef={})", existing.getId(), existing.getSku(), isDraft, isOrderReferenced);
                    existing.setStatus(ProductStatusEn.DISABLED);
                    existing.persist();
                }
            }
        }
    }

    /**
     * Shared validate-and-persist helper for variants and their price rows.
     * Used by both addProductInformation (create) and updateProductVariants (update).
     * <p>
     * For each variant DTO:
     * - If it carries an id, look it up and verify it belongs to this product (cross-product guard).
     * - If it carries no id, try to match by SKU against existing variants for this product.
     * - Create or update the variant entity accordingly.
     * - Persist each price via upsert, preventing duplicate price rows for the same type.
     * <p>
     * Assumptions: the ProductWriteValidator has already validated the aggregate
     * (non-blank SKUs, request-unique SKUs, exactly one positive RETAIL_PRICE, ownership).
     */
    private void persistVariantsWithPrices(ProductEntity product, List<ProductVariantDto> variantDtos)
    {
        // Load existing variants for SKU-based matching on create
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(product.getId());

        for (ProductVariantDto variantDto : variantDtos) {
            ProductVariantEntity variant = null;

            // Resolve existing variant by id or SKU
            if (variantDto.getId() != null && !variantDto.getId().isBlank()) {
                UUID variantId = UUID.fromString(variantDto.getId());
                variant = productVariantRepository.findByIdWithProduct(variantId);

                // Cross-product guard: reject if this variant belongs to another product
                if (variant != null && !product.getId().equals(variant.getProduct().getId())) {
                    throw new IllegalArgumentException("Variant id " + variantDto.getId() + " does not belong to product " + product.getId());
                }
            } else if (variantDto.getSku() != null) {
                // Try to find by SKU within this product's existing variants
                variant = existingVariants
                        .stream()
                        .filter(v -> v.getSku().equals(variantDto.getSku().trim()))
                        .findFirst()
                        .orElse(null);
            }

            if (variant == null) {
                // Create new variant
                variant = new ProductVariantEntity();
                variant.setProduct(product);
                variant.setSku(variantDto.getSku().trim());
                variant.setStockQuantity(variantDto.getStockQuantity());
                variant.setAttributesJson(variantDto.getAttributesJson());
                variant.setWeightKg(variantDto.getWeightKg());
                variant.setStatus(variantDto.getStatus() != null ? ProductStatusEn.valueOf(variantDto.getStatus()) : ProductStatusEn.ACTIVE);
                variant.persist();
                log.info("Created new variant with SKU: {} for product: {}", variantDto.getSku(), product.getId());
            } else {
                // Update existing variant fields (patch: only non-null values applied)
                if (variantDto.getSku() != null && !variantDto.getSku().isBlank()) {
                    variant.setSku(variantDto.getSku().trim());
                }
                if (variantDto.getStockQuantity() != null) {
                    variant.setStockQuantity(variantDto.getStockQuantity());
                }
                if (variantDto.getAttributesJson() != null) {
                    variant.setAttributesJson(variantDto.getAttributesJson());
                }
                if (variantDto.getWeightKg() != null) {
                    variant.setWeightKg(variantDto.getWeightKg());
                }
                if (variantDto.getStatus() != null) {
                    variant.setStatus(ProductStatusEn.valueOf(variantDto.getStatus()));
                }
                variant.persist();
                log.info("Updated variant with SKU: {} for product: {}", variant.getSku(), product.getId());
            }

            // Persist price rows via upsert (no duplicate rows per type)
            if (variantDto.getPrices() != null && !variantDto.getPrices().isEmpty()) {
                persistVariantPrices(variant, variantDto.getPrices());
            }
        }
    }

    /**
     * Upserts price rows for a variant. For each price DTO:
     * - If a price id is supplied, update that specific row (already ownership-validated).
     * - Otherwise find existing row by (variant, priceType) and update it — no duplicate.
     * - If no existing row exists, create one.
     * <p>
     * This prevents duplicate price rows for the same price type on a variant.
     */
    private void persistVariantPrices(ProductVariantEntity variant, List<VariantPriceDto> priceDtos)
    {
        if (variant == null || variant.getId() == null || priceDtos == null || priceDtos.isEmpty()) {
            return;
        }

        for (VariantPriceDto priceDto : priceDtos) {
            if (priceDto == null || priceDto.getPriceType() == null || priceDto.getPrice() == null) {
                continue;
            }

            final PriceTypeEn priceType;
            try {
                priceType = PriceTypeEn.valueOf(priceDto.getPriceType());
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping unsupported price type '{}' for variant {}", priceDto.getPriceType(), variant.getId());
                continue;
            }

            VariantPricesEntity price = null;

            // If the DTO carries a price id, update that specific row
            if (priceDto.getId() != null && !priceDto.getId().isBlank()) {
                price = VariantPricesEntity.findById(UUID.fromString(priceDto.getId()));
            }

            // Otherwise, find by variant + priceType to prevent duplicates
            if (price == null) {
                price = variantPricesRepository.findLatestByVariantAndType(variant.getId(), priceType);
            }

            // If still null, create a new row
            if (price == null) {
                price = new VariantPricesEntity();
                price.setVariant(variant);
                price.setPriceType(priceType);
            }

            price.setPrice(priceDto.getPrice());
            price.setPriceStartDate(priceDto.getPriceStartDate());
            price.setPriceEndDate(priceDto.getPriceEndDate());
            price.persist();
        }
    }

    @Transactional(value = TxType.REQUIRED)
    public void updateProductStatus(String id, String status)
    {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Product id is required");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Status is required");
        }

        UUID pid = UUID.fromString(id);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            throw new NotFoundException("Product not found with id: " + id);
        }

        product.setStatus(ProductStatusEn.valueOf(status));
        product.persist();
        log.info("Updated product {} status to {}", id, status);
    }

    @Transactional(value = TxType.REQUIRED)
    public void deleteProduct(String id)
    {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Product id is required");
        }

        UUID pid = UUID.fromString(id);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            throw new NotFoundException("Product not found with id: " + id);
        }

        // Load all child variants (including DISABLED) for reference check
        List<ProductVariantEntity> allVariants = productVariantRepository.findByVariantsForProductId(pid);

        // Check if ANY variant is referenced by orders
        boolean anyOrderReferenced = allVariants
                .stream()
                .anyMatch(v -> productVariantRepository.isReferencedByOrders(v.getId()));

        // Order history is the ONLY bar to physical deletion: a product whose
        // variants were never ordered may be hard-deleted in any status; one
        // with order references is archived (DISABLED) so orders keep their
        // variant rows. (Supersedes the old drafts-only hard-delete rule.)
        if (!anyOrderReferenced) {
            // CascadeType.ALL + orphanRemoval on ProductEntity.variants handles
            // cascading removal of variants, their prices, and their images.
            product.delete();
            log.info("Hard-deleted product {} — no order references", id);
        } else {
            // Archive: set product status to DISABLED, and disable all ACTIVE child variants
            product.setStatus(ProductStatusEn.DISABLED);
            product.persist();

            for (ProductVariantEntity variant : allVariants) {
                if (variant.getStatus() == ProductStatusEn.ACTIVE) {
                    variant.setStatus(ProductStatusEn.DISABLED);
                    variant.persist();
                }
            }
            log.info("Archived product {} — variants are order-referenced", id);
        }
    }

    public ProductInformationDto getProductInformationBySlug(String slug)
    {
        ProductEntity product = productRepository.findBySlugIgnoreCase(slug);
        if (product == null || product.getStatus() != ProductStatusEn.ACTIVE) {
            return null;
        }
        UUID pid = product.getId();
        // reload with joins so category/brand are populated
        product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }
        // Active-only read: exclude DISABLED variants for storefront detail
        return productMapper.mapToProductInformationDto(product, productVariantRepository.findActiveVariantsForProductId(pid));
    }
}
