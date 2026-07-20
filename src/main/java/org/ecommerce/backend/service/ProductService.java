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
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.enums.PriceTypeEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.ProductImageRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.ecommerce.common.repository.ProductVariantRepository;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.BrandRepository;
import org.ecommerce.common.repository.VariantPricesRepository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    public List<ProductListItemDto> getAllProducts(PageRequest pageRequest, FilterRequest filterRequest)
    {
        return enrichProductListItems(productRepository.findAllProductListItems(pageRequest, filterRequest, true));
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsByCategory(String categoryId, boolean includeSubCategories, PageRequest pageRequest, FilterRequest filterRequest, boolean ignoreStatus)
    {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("Category id is required");
        }

        UUID selectedCategoryId = UUID.fromString(categoryId);
        CategoryEntity selectedCategory = categoryRepository.findById(selectedCategoryId);
        if (selectedCategory == null) {
            throw new IllegalArgumentException("Category not found with id: " + categoryId);
        }

        List<UUID> categoryIds = includeSubCategories
                ? resolveCategoryScopeIds(selectedCategory)
                : List.of(selectedCategoryId);

        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);

        return enrichProductListItems(
                productRepository.findProductListItemsByCategoryIds(pageRequest, effectiveFilterRequest, categoryIds, ignoreStatus)
        );
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductListItemDto> getProductsByBrand(String brandId, PageRequest pageRequest, FilterRequest filterRequest, boolean ignoreStatus)
    {
        if (brandId == null || brandId.isBlank()) {
            throw new IllegalArgumentException("Brand id is required");
        }

        final UUID parsedBrandId;
        try {
            parsedBrandId = UUID.fromString(brandId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Brand id must be a valid UUID", e);
        }

        if (brandRepository.findById(parsedBrandId) == null) {
            throw new IllegalArgumentException("Brand not found with id: " + brandId);
        }

        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);
        List<Filter> filters = mutableFilters(effectiveFilterRequest);
        filters.add(new Filter("brand.id", FilterOperator.EQUALS, brandId));
        effectiveFilterRequest.setFilters(filters);

        return enrichProductListItems(
                productRepository.findAllProductListItems(pageRequest, effectiveFilterRequest, ignoreStatus)
        );
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getShoppingProducts(PageRequest pageRequest, FilterRequest filterRequest, boolean onSale, boolean ignoreStatus)
    {
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(
                productRepository.findShoppingProductEntities(pageRequest, effectiveFilterRequest, onSale, ignoreStatus), now, ignoreStatus);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSale(PageRequest pageRequest, boolean ignoreStatus)
    {
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(
                productRepository.findOnSaleProductEntities(pageRequest, ignoreStatus), now, ignoreStatus);
    }

    /**
     * Returns a paginated list of products for the admin product list table.
     * Supports optional filtering by status, categoryId, brandId, and search (name/SKU/barcode ILIKE).
     * For each product, resolves: primary variant SKU, thumbnail URL, lowest active retail price,
     * aggregated stock count, and derived stock level.
     */
    @Transactional(value = TxType.SUPPORTS)
    public PageResponse<AdminProductListItemDto> getAdminProductList(
            int pageIndex,
            int pageSize,
            String status,
            String categoryId,
            String brandId,
            String search)
    {
        LocalDateTime now = LocalDateTime.now();
        PageResponse<ProductEntity> page = productRepository.findAdminProductPage(
                pageIndex, pageSize, status, categoryId, brandId, search);

        List<AdminProductListItemDto> content = productListItemAssembler.buildAdminListItems(page.getContent(), now);

        return new PageResponse<>(content, page.getTotalElements(), page.getTotalPages(),
                page.getPageIndex(), page.getPageSize());
    }


    private List<ProductListItemDto> enrichProductListItems(List<ProductListItemDto> products)
    {
        return products.stream().map(product -> {
            if (product.id == null) {
                product.variantIds = List.of();
                product.imageName = null;
                return product;
            }

            UUID productId = UUID.fromString(product.id);

            product.variantIds = productVariantRepository.findByVariantsForProductId(productId)
                    .stream()
                    .map(v -> v.id.toString())
                    .collect(Collectors.toList());

            ProductImageEntity featuredImage = productImageRepository.findFeaturedByProductId(productId);
            product.imageName = featuredImage != null ? featuredImage.imageUrl : null;

            return product;
        }).collect(Collectors.toList());
    }

    private List<UUID> resolveCategoryScopeIds(CategoryEntity selectedCategory)
    {
        Set<UUID> scopedIds = new LinkedHashSet<>();
        scopedIds.add(selectedCategory.id);

        UUID groupParentId = selectedCategory.parent != null ? selectedCategory.parent.id : selectedCategory.id;
        List<CategoryEntity> groupedCategories = categoryRepository.list("parent.id", groupParentId);
        for (CategoryEntity category : groupedCategories) {
            if (category != null && category.id != null) {
                scopedIds.add(category.id);
            }
        }

        return new ArrayList<>(scopedIds);
    }

    @Transactional(value = TxType.SUPPORTS)
    public AdminProductStatsDto getProductStats()
    {
        AdminProductStatsDto stats = new AdminProductStatsDto();
        stats.total = productRepository.count();
        stats.active = productRepository.count("status", ProductStatusEn.ACTIVE);
        stats.pending = productRepository.count("status", ProductStatusEn.PENDING);
        stats.disabled = productRepository.count("status", ProductStatusEn.DISABLED);
        return stats;
    }

    @Transactional(value = TxType.SUPPORTS)
    public long productCount(FilterRequest filterRequest, boolean ignoreStatus)
    {
        return productRepository.count(applyActiveProductStatusFilter(filterRequest, ignoreStatus));
    }

    @Transactional(value = TxType.SUPPORTS)
    public long countShoppingProducts(FilterRequest filterRequest, boolean onSale, boolean ignoreStatus)
    {
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);
        return productRepository.countShoppingProducts(effectiveFilterRequest, onSale, ignoreStatus);
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
        return filterRequest.getFilters() != null
                ? new ArrayList<>(filterRequest.getFilters())
                : new ArrayList<>();
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getTopBestSellers()
    {
        LocalDateTime now = LocalDateTime.now();
        return productListItemAssembler.buildShoppingListItems(
                productRepository.findTopBestSellerEntities(), now, true);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductVariantDto> getVariantsByIds(List<String> ids)
    {
        List<UUID> uuidIds = ids.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList());
        return productMapper.mapVariantEntitiesToDtos(
                productVariantRepository.findByIdsWithProduct(uuidIds));
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
        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findActiveVariantsForProductId(pid));
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(ProductInformationDto input)
    {
        // Validate the complete aggregate before any persistence
        productWriteValidator.validateForCreate(input);

        // Create new product entity
        ProductEntity product = new ProductEntity();
        productMapper.applyCreatableFields(input.product, product);

        // Link categories if provided
        if (input.product.categories != null && !input.product.categories.isEmpty()) {
            for (CategoryDto categoryDto : input.product.categories) {
                if (categoryDto.id != null) {
                    UUID categoryId = categoryDto.id;
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.categories.add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.product.category != null && input.product.category.id != null) {
            // Backward compatibility: handle single category
            UUID categoryId = input.product.category.id;
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.categories.add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Link brand if provided
        if (input.product.brand != null && input.product.brand.id != null) {
            UUID brandId = input.product.brand.id;
            product.brand = brandRepository.findById(brandId);
            if (product.brand != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save product
        product.persist();
        log.info("Created new product with ID: {}", product.id);

        // Persist variants and their prices
        if (input.variants != null && !input.variants.isEmpty()) {
            persistVariantsWithPrices(product, input.variants);
        }

        // Persist images: extract manifest from payload variant index 0
        List<ProductImageDto> imageManifest = extractImageManifest(input);
        updateProductImages(product.id, imageManifest);

        // Return the full aggregate via the active-only read path
        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findActiveVariantsForProductId(product.id));
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
        productMapper.applyEditableFields(input.product, product);

        // Update categories if provided
        if (input.product.categories != null && !input.product.categories.isEmpty()) {
            product.categories.clear();
            for (CategoryDto categoryDto : input.product.categories) {
                if (categoryDto.id != null) {
                    UUID categoryId = categoryDto.id;
                    CategoryEntity category = categoryRepository.findById(categoryId);
                    if (category != null) {
                        product.categories.add(category);
                        log.info("Linked category with ID: {}", categoryId);
                    } else {
                        log.warn("Category not found with ID: {}", categoryId);
                    }
                }
            }
        } else if (input.product.category != null && input.product.category.id != null) {
            // Backward compatibility: handle single category
            product.categories.clear();
            UUID categoryId = input.product.category.id;
            CategoryEntity category = categoryRepository.findById(categoryId);
            if (category != null) {
                product.categories.add(category);
                log.info("Linked category with ID: {}", categoryId);
            } else {
                log.warn("Category not found with ID: {}", categoryId);
            }
        }

        // Update brand if provided
        if (input.product.brand != null && input.product.brand.id != null) {
            UUID brandId = input.product.brand.id;
            product.brand = brandRepository.findById(brandId);
            if (product.brand != null) {
                log.info("Linked brand with ID: {}", brandId);
            } else {
                log.warn("Brand not found with ID: {}", brandId);
            }
        }

        // Save updated product
        product.persist();
        log.info("Updated product with ID: {}", product.id);

        // Handle product variants updates
        if (input.variants != null && !input.variants.isEmpty()) {
            updateProductVariants(pid, input.variants);
        }

        // Persist images: extract manifest from payload variant index 0 (after variant upsert)
        List<ProductImageDto> imageManifest = extractImageManifest(input);
        updateProductImages(pid, imageManifest);

        // Active-only read: exclude DISABLED variants from the returned aggregate
        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findActiveVariantsForProductId(pid));
    }

    /**
     * Extracts the product-level image manifest from the payload.
     * By convention, the frontend carries the manifest on variant index 0's images[].
     * This is a transport convention only — it does NOT define persistence ownership.
     */
    private List<ProductImageDto> extractImageManifest(ProductInformationDto input) {
        if (input.variants == null || input.variants.isEmpty()) {
            return List.of();
        }
        ProductVariantDto firstVariant = input.variants.get(0);
        if (firstVariant.images == null || firstVariant.images.isEmpty()) {
            return List.of();
        }
        return firstVariant.images;
    }

    /**
     * Reconciles the product-wide image manifest against existing product image associations.
     *
     * The manifest is a single ordered list of images that the product editor carries on
     * payload variant index 0 (transport convention only). After all variant upserts, this
     * method determines the "owner variant" — the ACTIVE variant with the lowest UUID
     * (sorted by UUID.toString()) — and persists the manifest on it.
     *
     * Steps:
     * 1. Determine the owner variant (active, lowest UUID).
     * 2. Load all existing ProductImageEntity rows for this product (across all variants).
     * 3. Reconcile: add new images, remove images not in the manifest, update sortOrder/isFeatured.
     * 4. Normalise: move any existing images on a non-owner variant to the owner variant.
     */
    private void updateProductImages(UUID productId, List<ProductImageDto> manifest) {
        log.info("Updating images for product ID: {}", productId);

        if (manifest == null) {
            manifest = List.of();
        }

        // 1. Determine the owner variant: active variant with the lowest UUID (string sort)
        List<ProductVariantEntity> allVariants = productVariantRepository.findByVariantsForProductId(productId);
        ProductVariantEntity ownerVariant = allVariants.stream()
                .filter(v -> v.status == ProductStatusEn.ACTIVE)
                .min((a, b) -> a.id.toString().compareTo(b.id.toString()))
                .orElse(null);

        if (ownerVariant == null) {
            // No active variant — cannot persist images; remove all existing
            List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);
            for (ProductImageEntity image : existingImages) {
                if (image.productVariant != null && image.productVariant.images != null) {
                    image.productVariant.images.remove(image);
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
            existingById.put(img.id, img);
        }

        // 3. Reconcile: track which existing image ids are still in the manifest
        Set<UUID> manifestImageIds = new LinkedHashSet<>();

        for (int i = 0; i < manifest.size(); i++) {
            ProductImageDto imgDto = manifest.get(i);

            if (imgDto.id != null && !imgDto.id.isBlank()) {
                // Existing image — update sortOrder, isFeatured, and normalise to owner
                UUID imageId = UUID.fromString(imgDto.id);
                manifestImageIds.add(imageId);

                ProductImageEntity existing = existingById.get(imageId);
                if (existing != null) {
                    existing.sortOrder = i;
                    existing.isFeatured = imgDto.isFeatured;
                    // Normalise: move to owner variant if not already there
                    if (!ownerVariant.id.equals(existing.productVariant.id)) {
                        existing.productVariant = ownerVariant;
                    }
                    existing.persist();
                }
            } else {
                // New image — create entity on the owner variant
                ProductImageEntity newImage = new ProductImageEntity();
                newImage.productVariant = ownerVariant;
                newImage.imageUrl = imgDto.imageUrl;
                newImage.sortOrder = i;
                newImage.isFeatured = imgDto.isFeatured;
                newImage.persist();
                log.info("Created new image association for product {}: {}", productId, imgDto.imageUrl);
            }
        }

        // 4. Remove images not in the manifest
        for (ProductImageEntity existing : existingImages) {
            if (!manifestImageIds.contains(existing.id)) {
                // Remove from variant's managed collection to prevent orphanRemoval conflicts
                if (existing.productVariant != null && existing.productVariant.images != null) {
                    existing.productVariant.images.remove(existing);
                }
                existing.delete();
                log.info("Removed image association {} from product {}", existing.id, productId);
            }
        }
    }

    /**
     * Updates product variants and their prices, then removes variants absent from
     * the payload per the Deletion Policy (Req 9).
     */
    private void updateProductVariants(UUID productId, List<ProductVariantDto> newVariants) {
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
        Set<UUID> payloadVariantIds = newVariants.stream()
                .filter(v -> v.id != null && !v.id.isBlank())
                .map(v -> UUID.fromString(v.id))
                .collect(Collectors.toSet());

        // Also include variants that were matched by SKU (newly created variants won't be in existing list)
        Set<String> payloadSkus = newVariants.stream()
                .filter(v -> v.sku != null)
                .map(v -> v.sku.trim())
                .collect(Collectors.toSet());

        // Remove variants absent from the payload per the Deletion Policy
        for (ProductVariantEntity existing : existingVariants) {
            boolean inPayloadById = payloadVariantIds.contains(existing.id);
            boolean inPayloadBySku = payloadSkus.contains(existing.sku);

            if (!inPayloadById && !inPayloadBySku) {
                // This variant was removed from the editor — apply Deletion Policy
                boolean isDraft = product.status == ProductStatusEn.PENDING;
                boolean isOrderReferenced = productVariantRepository.isReferencedByOrders(existing.id);

                if (isDraft && !isOrderReferenced) {
                    // Hard-delete: product is draft and variant is not order-referenced
                    log.info("Hard-deleting variant {} (SKU: {}) — draft product, no order references",
                            existing.id, existing.sku);
                    existing.delete();
                } else {
                    // Soft-delete: set status to DISABLED
                    log.info("Soft-deleting variant {} (SKU: {}) — setting status to DISABLED (draft={}, orderRef={})",
                            existing.id, existing.sku, isDraft, isOrderReferenced);
                    existing.status = ProductStatusEn.DISABLED;
                    existing.persist();
                }
            }
        }
    }

    /**
     * Shared validate-and-persist helper for variants and their price rows.
     * Used by both addProductInformation (create) and updateProductVariants (update).
     *
     * For each variant DTO:
     * - If it carries an id, look it up and verify it belongs to this product (cross-product guard).
     * - If it carries no id, try to match by SKU against existing variants for this product.
     * - Create or update the variant entity accordingly.
     * - Persist each price via upsert, preventing duplicate price rows for the same type.
     *
     * Assumptions: the ProductWriteValidator has already validated the aggregate
     * (non-blank SKUs, request-unique SKUs, exactly one positive RETAIL_PRICE, ownership).
     */
    private void persistVariantsWithPrices(ProductEntity product, List<ProductVariantDto> variantDtos) {
        // Load existing variants for SKU-based matching on create
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(product.id);

        for (ProductVariantDto variantDto : variantDtos) {
            ProductVariantEntity variant = null;

            // Resolve existing variant by id or SKU
            if (variantDto.id != null && !variantDto.id.isBlank()) {
                UUID variantId = UUID.fromString(variantDto.id);
                variant = productVariantRepository.findByIdWithProduct(variantId);

                // Cross-product guard: reject if this variant belongs to another product
                if (variant != null && !product.id.equals(variant.product.id)) {
                    throw new IllegalArgumentException(
                            "Variant id " + variantDto.id + " does not belong to product " + product.id);
                }
            } else if (variantDto.sku != null) {
                // Try to find by SKU within this product's existing variants
                variant = existingVariants.stream()
                        .filter(v -> v.sku.equals(variantDto.sku.trim()))
                        .findFirst()
                        .orElse(null);
            }

            if (variant == null) {
                // Create new variant
                variant = new ProductVariantEntity();
                variant.product = product;
                variant.sku = variantDto.sku.trim();
                variant.stockQuantity = variantDto.stockQuantity;
                variant.attributesJson = variantDto.attributesJson;
                variant.weightKg = variantDto.weightKg;
                variant.status = variantDto.status != null
                        ? ProductStatusEn.valueOf(variantDto.status)
                        : ProductStatusEn.ACTIVE;
                variant.persist();
                log.info("Created new variant with SKU: {} for product: {}", variantDto.sku, product.id);
            } else {
                // Update existing variant fields (patch: only non-null values applied)
                if (variantDto.sku != null && !variantDto.sku.isBlank()) {
                    variant.sku = variantDto.sku.trim();
                }
                if (variantDto.stockQuantity != null) {
                    variant.stockQuantity = variantDto.stockQuantity;
                }
                if (variantDto.attributesJson != null) {
                    variant.attributesJson = variantDto.attributesJson;
                }
                if (variantDto.weightKg != null) {
                    variant.weightKg = variantDto.weightKg;
                }
                if (variantDto.status != null) {
                    variant.status = ProductStatusEn.valueOf(variantDto.status);
                }
                variant.persist();
                log.info("Updated variant with SKU: {} for product: {}", variant.sku, product.id);
            }

            // Persist price rows via upsert (no duplicate rows per type)
            if (variantDto.prices != null && !variantDto.prices.isEmpty()) {
                persistVariantPrices(variant, variantDto.prices);
            }
        }
    }

    /**
     * Upserts price rows for a variant. For each price DTO:
     * - If a price id is supplied, update that specific row (already ownership-validated).
     * - Otherwise find existing row by (variant, priceType) and update it — no duplicate.
     * - If no existing row exists, create one.
     *
     * This prevents duplicate price rows for the same price type on a variant.
     */
    private void persistVariantPrices(ProductVariantEntity variant, List<VariantPriceDto> priceDtos) {
        if (variant == null || variant.id == null || priceDtos == null || priceDtos.isEmpty()) {
            return;
        }

        for (VariantPriceDto priceDto : priceDtos) {
            if (priceDto == null || priceDto.priceType == null || priceDto.price == null) {
                continue;
            }

            final PriceTypeEn priceType;
            try {
                priceType = PriceTypeEn.valueOf(priceDto.priceType);
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping unsupported price type '{}' for variant {}", priceDto.priceType, variant.id);
                continue;
            }

            VariantPricesEntity price = null;

            // If the DTO carries a price id, update that specific row
            if (priceDto.id != null && !priceDto.id.isBlank()) {
                price = VariantPricesEntity.findById(UUID.fromString(priceDto.id));
            }

            // Otherwise, find by variant + priceType to prevent duplicates
            if (price == null) {
                price = variantPricesRepository.findLatestByVariantAndType(variant.id, priceType);
            }

            // If still null, create a new row
            if (price == null) {
                price = new VariantPricesEntity();
                price.variant = variant;
                price.priceType = priceType;
            }

            price.price = priceDto.price;
            price.priceStartDate = priceDto.priceStartDate;
            price.priceEndDate = priceDto.priceEndDate;
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

        product.status = ProductStatusEn.valueOf(status);
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
        boolean anyOrderReferenced = allVariants.stream()
                .anyMatch(v -> productVariantRepository.isReferencedByOrders(v.id));

        boolean isDraft = product.status == ProductStatusEn.PENDING;

        if (isDraft && !anyOrderReferenced) {
            // Hard-delete: product is PENDING and no child variant is order-referenced.
            // CascadeType.ALL + orphanRemoval on ProductEntity.variants handles
            // cascading removal of variants, their prices, and their images.
            product.delete();
            log.info("Hard-deleted product {} — draft with no order references", id);
        } else {
            // Soft-delete: set product status to DISABLED, and disable all ACTIVE child variants
            product.status = ProductStatusEn.DISABLED;
            product.persist();

            for (ProductVariantEntity variant : allVariants) {
                if (variant.status == ProductStatusEn.ACTIVE) {
                    variant.status = ProductStatusEn.DISABLED;
                    variant.persist();
                }
            }
            log.info("Soft-deleted product {} — set to DISABLED (draft={}, orderRef={})",
                    id, isDraft, anyOrderReferenced);
        }
    }

    public ProductInformationDto getProductInformationBySlug(String slug)
    {
        ProductEntity product = productRepository.findBySlugIgnoreCase(slug);
        if (product == null || product.status != ProductStatusEn.ACTIVE) {
            return null;
        }
        UUID pid = product.id;
        // reload with joins so category/brand are populated
        product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }
        // Active-only read: exclude DISABLED variants for storefront detail
        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findActiveVariantsForProductId(pid));
    }
}
