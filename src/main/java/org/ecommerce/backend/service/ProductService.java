package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.ProductMapper;
import org.ecommerce.common.dto.*;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.common.enums.ProductTypeEn;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
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
    public List<ProductShoppingListItemDto> getShoppingProducts(PageRequest pageRequest, FilterRequest filterRequest, boolean ignoreStatus)
    {
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);
        return productRepository.findShoppingProductList(pageRequest, effectiveFilterRequest, ignoreStatus);
    }

    @Transactional(value = TxType.SUPPORTS)
    public List<ProductShoppingListItemDto> getProductsOnSale(PageRequest pageRequest, boolean ignoreStatus)
    {
        return productRepository.findOnSaleShoppingProductList(pageRequest, ignoreStatus);
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
        int effectivePageSize = Math.min(Math.max(pageSize, 1), 100);
        int effectivePageIndex = Math.max(pageIndex, 0);

        EntityManager em = productRepository.getEntityManager();
        LocalDateTime now = LocalDateTime.now();

        // Build dynamic WHERE clause
        StringBuilder whereClause = new StringBuilder("WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (status != null && !status.isBlank()) {
            whereClause.append(" AND p.status = :status");
            params.put("status", ProductStatusEn.valueOf(status));
        }

        if (categoryId != null && !categoryId.isBlank()) {
            whereClause.append(" AND EXISTS (SELECT 1 FROM p.categories c WHERE c.id = :categoryId)");
            params.put("categoryId", UUID.fromString(categoryId));
        }

        if (brandId != null && !brandId.isBlank()) {
            whereClause.append(" AND p.brand.id = :brandId");
            params.put("brandId", UUID.fromString(brandId));
        }

        if (search != null && !search.isBlank()) {
            String searchPattern = "%" + search.trim().toLowerCase() + "%";
            whereClause.append(" AND (LOWER(p.name) LIKE :search")
                    .append(" OR EXISTS (SELECT 1 FROM ProductVariantEntity sv WHERE sv.product = p AND LOWER(sv.sku) LIKE :search)")
                    .append(")");
            params.put("search", searchPattern);
        }

        // Count query
        String countHql = "SELECT COUNT(p) FROM ProductEntity p " + whereClause;
        TypedQuery<Long> countQuery = em.createQuery(countHql, Long.class);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            countQuery.setParameter(entry.getKey(), entry.getValue());
        }
        long totalElements = countQuery.getSingleResult();
        int totalPages = effectivePageSize > 0
                ? (int) Math.ceil((double) totalElements / effectivePageSize)
                : 0;

        // Fetch products query
        String fetchHql = "SELECT DISTINCT p FROM ProductEntity p " +
                "LEFT JOIN FETCH p.categories " +
                "LEFT JOIN FETCH p.brand " +
                whereClause +
                " ORDER BY p.name ASC";
        TypedQuery<ProductEntity> fetchQuery = em.createQuery(fetchHql, ProductEntity.class);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            fetchQuery.setParameter(entry.getKey(), entry.getValue());
        }
        fetchQuery.setFirstResult(effectivePageIndex * effectivePageSize);
        fetchQuery.setMaxResults(effectivePageSize);

        List<ProductEntity> products = fetchQuery.getResultList();

        // Map products to admin DTOs
        List<AdminProductListItemDto> content = products.stream()
                .map(product -> toAdminProductListItemDto(product, now))
                .collect(Collectors.toList());

        return new PageResponse<>(content, totalElements, totalPages, effectivePageIndex, effectivePageSize);
    }

    private AdminProductListItemDto toAdminProductListItemDto(ProductEntity product, LocalDateTime now)
    {
        AdminProductListItemDto dto = new AdminProductListItemDto();
        dto.id = product.id != null ? product.id.toString() : null;
        dto.name = product.name;
        dto.slug = product.slug;
        dto.status = product.status != null ? product.status.name() : null;

        // Category — use first category
        CategoryEntity cat = product.getCategory();
        if (cat != null) {
            CategoryDto catDto = new CategoryDto();
            catDto.setId(cat.id);
            catDto.setName(cat.name);
            dto.category = catDto;
        }

        if (product.id == null) {
            return dto;
        }

        UUID productId = product.id;
        EntityManager em = productRepository.getEntityManager();

        // Primary variant (first by id ASC) — resolve SKU and thumbnail
        List<ProductVariantEntity> variants = em.createQuery(
                        "SELECT v FROM ProductVariantEntity v WHERE v.product.id = :productId ORDER BY v.id ASC",
                        ProductVariantEntity.class)
                .setParameter("productId", productId)
                .getResultList();

        if (!variants.isEmpty()) {
            ProductVariantEntity primaryVariant = variants.get(0);
            dto.sku = primaryVariant.sku;

            // Thumbnail from primary variant's first image
            List<ProductImageEntity> images = em.createQuery(
                            "SELECT pi FROM ProductImageEntity pi WHERE pi.productVariant.id = :variantId " +
                                    "ORDER BY CASE WHEN pi.isFeatured = true THEN 0 ELSE 1 END ASC, pi.sortOrder ASC, pi.id ASC",
                            ProductImageEntity.class)
                    .setParameter("variantId", primaryVariant.id)
                    .setMaxResults(1)
                    .getResultList();
            if (!images.isEmpty()) {
                dto.thumbnailUrl = images.get(0).imageUrl;
            }
        }

        // Aggregated stock count across all variants
        Long totalStock = em.createQuery(
                        "SELECT COALESCE(SUM(v.stockQuantity), 0) FROM ProductVariantEntity v WHERE v.product.id = :productId",
                        Long.class)
                .setParameter("productId", productId)
                .getSingleResult();
        dto.stockCount = totalStock != null ? totalStock.intValue() : 0;

        // Derive stock level
        dto.stockLevel = deriveStockLevel(dto.stockCount);

        // Lowest active retail price across active variants
        List<VariantPricesEntity> retailPrices = em.createQuery(
                        "SELECT vp FROM VariantPricesEntity vp JOIN vp.variant v " +
                                "WHERE v.product.id = :productId " +
                                "AND v.status = :activeStatus " +
                                "AND vp.priceType = :priceType " +
                                "AND (vp.priceStartDate IS NULL OR vp.priceStartDate <= :now) " +
                                "AND (vp.priceEndDate IS NULL OR vp.priceEndDate >= :now) " +
                                "ORDER BY vp.price ASC",
                        VariantPricesEntity.class)
                .setParameter("productId", productId)
                .setParameter("activeStatus", ProductStatusEn.ACTIVE)
                .setParameter("priceType", PriceTypeEn.RETAIL_PRICE)
                .setParameter("now", now)
                .setMaxResults(1)
                .getResultList();

        if (!retailPrices.isEmpty()) {
            dto.retailPrice = retailPrices.get(0).price.toPlainString();
        }

        return dto;
    }

    private String deriveStockLevel(int stockCount)
    {
        if (stockCount <= 0) {
            return "OUT_OF_STOCK";
        } else if (stockCount <= 10) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
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
    public long countShoppingProducts(FilterRequest filterRequest, boolean ignoreStatus)
    {
        FilterRequest effectiveFilterRequest = applyActiveProductStatusFilter(filterRequest, ignoreStatus);
        return productRepository.countShoppingProducts(effectiveFilterRequest, ignoreStatus);
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
        return productRepository.findTopBestSellers();
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

        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid));
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto addProductInformation(ProductInformationDto input)
    {
        if (input == null) {
            log.error("ProductInformationDto is null");
            throw new IllegalArgumentException("Product information cannot be null");
        }

        // Create new product entity
        ProductEntity product = new ProductEntity();
        product.name = input.product.name;
        product.slug = input.product.slug;
        product.description = input.product.description;
        product.shorDescription = input.product.shortDescription;
        product.productType = input.product.productType != null ? ProductTypeEn.valueOf(input.product.productType) : ProductTypeEn.SIMPLE;
        product.status = input.product.status != null ? ProductStatusEn.valueOf(input.product.status) : ProductStatusEn.ACTIVE;

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

        // TODO: Handle product images and variants creation
        // This would require additional repository methods or separate transactions

        return productMapper.mapToProductInformationDto(
                product,
                List.of());
    }

    @Transactional(value = TxType.REQUIRED)
    public ProductInformationDto updateProductInformation(String productId, ProductInformationDto input)
    {
        if (input == null) {
            log.error("ProductInformationDto is null");
            throw new IllegalArgumentException("Product information cannot be null");
        }

        UUID pid = UUID.fromString(productId);
        ProductEntity product = productRepository.findByIdWithCategoryAndBrand(pid);

        if (product == null) {
            log.error("Product not found with ID: {}", productId);
            throw new IllegalArgumentException("Product not found");
        }

        // Update product information
        if (input.product.name != null && !input.product.name.isBlank()) {
            product.name = input.product.name;
        }
        if (input.product.slug != null && !input.product.slug.isBlank()) {
            product.slug = input.product.slug;
        }
        if (input.product.description != null && !input.product.description.isBlank()) {
            product.description = input.product.description;
        }
        if (input.product.shortDescription != null && !input.product.shortDescription.isBlank()) {
            product.shorDescription = input.product.shortDescription;
        }
        if (input.product.productType != null && !input.product.productType.isBlank()) {
            product.productType = ProductTypeEn.valueOf(input.product.productType);
        }

        if (input.product.status != null && !input.product.status.isBlank()) {
            product.status = ProductStatusEn.valueOf(input.product.status);
        }

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

        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid));
    }

    /**
     * Updates product images by replacing existing images with new ones
     */
    private void updateProductImages(UUID productId, List<ProductImageDto> newImages) {
        log.info("Updating images for product ID: {}", productId);

        // Delete all existing images for this product
        List<ProductImageEntity> existingImages = productImageRepository.findByProductId(productId);
        for (ProductImageEntity image : existingImages) {
            image.delete();
        }

        // Note: ProductImageEntity requires a ProductVariantEntity relationship
        // Images are typically linked to variants, not directly to products
        // This is a limitation of the current schema - would need product variants to exist first
        // TODO: Once variants are created, associate images with the appropriate variant
    }

    /**
     * Updates product variants and their prices
     */
    private void updateProductVariants(UUID productId, List<ProductVariantDto> newVariants) {
        log.info("Updating variants for product ID: {}", productId);

        // Get existing variants for this product
        List<ProductVariantEntity> existingVariants = productVariantRepository.findByVariantsForProductId(productId);

        // Update existing variants or create new ones
        for (ProductVariantDto variantDto : newVariants) {
            ProductVariantEntity variant = null;

            // Check if variant with this SKU already exists
            if (variantDto.id != null && !variantDto.id.isBlank()) {
                variant = productVariantRepository.findByIdWithProduct(UUID.fromString(variantDto.id));
            } else if (variantDto.sku != null) {
                // Try to find by SKU
                variant = existingVariants.stream()
                        .filter(v -> v.sku.equals(variantDto.sku))
                        .findFirst()
                        .orElse(null);
            }

            if (variant == null) {
                // Create new variant
                variant = new ProductVariantEntity();
                variant.product = productRepository.findByIdWithCategoryAndBrand(productId);
                variant.sku = variantDto.sku;
                variant.stockQuantity = variantDto.stockQuantity;
                variant.attributesJson = variantDto.attributesJson;
                variant.weightKg = variantDto.weightKg;
                variant.status = variantDto.status != null ? ProductStatusEn.valueOf(variantDto.status) : ProductStatusEn.ACTIVE;
                variant.persist();
                log.info("Created new variant with SKU: {}", variantDto.sku);
            } else {
                // Update existing variant
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
                log.info("Updated variant with SKU: {}", variantDto.sku);
            }

            if (variantDto.prices != null && !variantDto.prices.isEmpty()) {
                updateVariantPrices(variant, variantDto.prices);
            }
            //TODO:: Update Pricing
        }

        // Optionally delete variants not in the new list
        // For now, we'll keep this as a TODO to preserve existing data
        // TODO: Implement logic to delete variants not provided in the update
    }

    private void updateVariantPrices(ProductVariantEntity variant, List<VariantPriceDto> newPrices) {
        if (variant == null || variant.id == null || newPrices == null || newPrices.isEmpty()) {
            return;
        }

        for (VariantPriceDto priceDto : newPrices) {
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

            VariantPricesEntity price = variantPricesRepository.findLatestByVariantAndType(variant.id, priceType);
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

        product.delete();
        log.info("Deleted product with id: {}", id);
    }

    public ProductInformationDto getProductInformationBySlug(String slug)
    {
        ProductEntity product = productRepository.findBySlugIgnoreCase(slug);
        if (product == null) {
            return null;
        }
        UUID pid = product.id;
        // reload with joins so category/brand are populated
        product = productRepository.findByIdWithCategoryAndBrand(pid);
        if (product == null) {
            return null;
        }
        return productMapper.mapToProductInformationDto(
                product,
                productVariantRepository.findByVariantsForProductId(pid));
    }
}
