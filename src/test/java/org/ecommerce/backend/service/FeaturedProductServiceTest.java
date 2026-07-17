package org.ecommerce.backend.service;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.common.dto.AdminProductListItemDto;
import org.ecommerce.common.dto.FeaturedProductResultDto;
import org.ecommerce.common.dto.ProductShoppingListItemDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.ProductImageEntity;
import org.ecommerce.common.entity.VariantPricesEntity;
import org.ecommerce.backend.exception.FeaturedCapExceededException;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.CategoryRepository;
import org.ecommerce.common.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FeaturedProductService}.
 *
 * Requirements: 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.6, 3.1, 4.1, 4.2, 4.3
 */
@QuarkusTest
class FeaturedProductServiceTest {

    @Inject
    FeaturedProductService featuredProductService;

    @InjectMock
    ProductRepository productRepository;

    @InjectMock
    CategoryRepository categoryRepository;

    @InjectMock
    org.ecommerce.backend.assembler.ProductListItemAssembler productListItemAssembler;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(ProductEntity.class);

        // List-item DTO assembly now lives in ProductRepository (data layer) and is
        // tested there (ProductListItemMappingCharacterizationIT / DedupProofIT). These
        // service unit tests only verify delegation + ordering, so stub the build
        // methods to echo id/name/status from the passed entity.
        lenient().when(productListItemAssembler.buildAdminListItem(any(ProductEntity.class), any()))
                .thenAnswer(inv -> {
                    ProductEntity p = inv.getArgument(0);
                    AdminProductListItemDto dto = new AdminProductListItemDto();
                    dto.id = p.id == null ? null : p.id.toString();
                    dto.name = p.name;
                    dto.status = p.status == null ? null : p.status.name();
                    return dto;
                });
        lenient().when(productListItemAssembler.buildShoppingListItem(any(ProductEntity.class), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    ProductEntity p = inv.getArgument(0);
                    ProductShoppingListItemDto dto = new ProductShoppingListItemDto();
                    dto.id = p.id == null ? null : p.id.toString();
                    dto.name = p.name;
                    dto.status = p.status == null ? null : p.status.name();
                    return dto;
                });
    }

    // ── setFeatured: happy path ─────────────────────────────────────────────

    @Test
    void setFeatured_shouldSetIsFeaturedTrue_whenBelowCap() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Test Product", ProductStatusEn.ACTIVE));
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);
        when(ProductEntity.count("isFeatured", true)).thenReturn(10L);

        FeaturedProductResultDto result = featuredProductService.setFeatured(productId, true);

        assertNotNull(result);
        assertEquals(productId.toString(), result.productId);
        assertTrue(result.featured);
        assertTrue(product.isFeatured);
    }

    // ── setFeatured: cap exceeded ───────────────────────────────────────────

    @Test
    void setFeatured_shouldThrowFeaturedCapExceededException_whenCountIs50() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Test Product", ProductStatusEn.ACTIVE));
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);
        when(ProductEntity.count("isFeatured", true)).thenReturn(50L);

        FeaturedCapExceededException ex = assertThrows(
                FeaturedCapExceededException.class,
                () -> featuredProductService.setFeatured(productId, true)
        );

        assertEquals("Featured limit of 50 reached. Remove a product before adding another.", ex.getMessage());
        assertFalse(product.isFeatured);
    }

    // ── setFeatured: not found ──────────────────────────────────────────────

    @Test
    void setFeatured_shouldThrowNotFoundException_forMissingProduct() {
        UUID productId = UUID.randomUUID();

        when(productRepository.findById(productId)).thenReturn(null);

        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> featuredProductService.setFeatured(productId, true)
        );

        assertTrue(ex.getMessage().contains(productId.toString()));
    }

    // ── setFeatured: succeeds for all statuses ──────────────────────────────

    @Test
    void setFeatured_shouldSucceed_forActiveProduct() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Active Product", ProductStatusEn.ACTIVE));
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);
        when(ProductEntity.count("isFeatured", true)).thenReturn(5L);

        FeaturedProductResultDto result = featuredProductService.setFeatured(productId, true);

        assertTrue(result.featured);
    }

    @Test
    void setFeatured_shouldSucceed_forPendingProduct() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Pending Product", ProductStatusEn.PENDING));
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);
        when(ProductEntity.count("isFeatured", true)).thenReturn(5L);

        FeaturedProductResultDto result = featuredProductService.setFeatured(productId, true);

        assertTrue(result.featured);
    }

    @Test
    void setFeatured_shouldSucceed_forDisabledProduct() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Disabled Product", ProductStatusEn.DISABLED));
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);
        when(ProductEntity.count("isFeatured", true)).thenReturn(5L);

        FeaturedProductResultDto result = featuredProductService.setFeatured(productId, true);

        assertTrue(result.featured);
    }

    // ── setFeatured(id, false): unfeaturing ─────────────────────────────────

    @Test
    void setFeatured_shouldSetIsFeaturedFalse_whenCalledWithFalse() {
        UUID productId = UUID.randomUUID();
        ProductEntity product = spy(createProduct(productId, "Featured Product", ProductStatusEn.ACTIVE));
        product.isFeatured = true;
        doNothing().when(product).persist();

        when(productRepository.findById(productId)).thenReturn(product);

        FeaturedProductResultDto result = featuredProductService.setFeatured(productId, false);

        assertNotNull(result);
        assertEquals(productId.toString(), result.productId);
        assertFalse(result.featured);
        assertFalse(product.isFeatured);
    }

    // ── getFeaturedProductsForAdmin ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedProductsForAdmin_shouldReturnAllFeaturedSortedByName() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        ProductEntity product1 = createProduct(id1, "Alpha Widget", ProductStatusEn.ACTIVE);
        ProductEntity product2 = createProduct(id2, "Beta Gadget", ProductStatusEn.PENDING);
        ProductEntity product3 = createProduct(id3, "Charlie Device", ProductStatusEn.DISABLED);

        List<ProductEntity> featuredProducts = List.of(product1, product2, product3);

        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn((List) featuredProducts);
        when(ProductEntity.find("isFeatured = true order by name asc")).thenReturn(query);

        // Mock EntityManager for toAdminProductListItemDto
        EntityManager em = mockEntityManagerForAdminDto();
        when(productRepository.getEntityManager()).thenReturn(em);

        List<AdminProductListItemDto> result = featuredProductService.getFeaturedProductsForAdmin();

        assertEquals(3, result.size());
        assertEquals("Alpha Widget", result.get(0).name);
        assertEquals("Beta Gadget", result.get(1).name);
        assertEquals("Charlie Device", result.get(2).name);
        assertEquals(id1.toString(), result.get(0).id);
        assertEquals(id2.toString(), result.get(1).id);
        assertEquals(id3.toString(), result.get(2).id);
        // Statuses are mapped
        assertEquals("ACTIVE", result.get(0).status);
        assertEquals("PENDING", result.get(1).status);
        assertEquals("DISABLED", result.get(2).status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedProductsForAdmin_shouldReturnEmptyList_whenNoProductsFeatured() {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(Collections.emptyList());
        when(ProductEntity.find("isFeatured = true order by name asc")).thenReturn(query);

        List<AdminProductListItemDto> result = featuredProductService.getFeaturedProductsForAdmin();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ── getFeaturedShoppingProducts ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedShoppingProducts_shouldReturnOnlyFeaturedActiveProducts() {
        UUID id1 = UUID.randomUUID();
        ProductEntity product1 = createProduct(id1, "Active Featured", ProductStatusEn.ACTIVE);
        product1.isFeatured = true;

        List<ProductEntity> products = List.of(product1);

        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn((List) products);
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);

        // Mock EntityManager for toShoppingListItemDto
        EntityManager em = mockEntityManagerForShoppingDto();
        when(productRepository.getEntityManager()).thenReturn(em);

        List<ProductShoppingListItemDto> result = featuredProductService.getFeaturedShoppingProducts(null, null);

        assertEquals(1, result.size());
        assertEquals("Active Featured", result.get(0).name);
        assertEquals(id1.toString(), result.get(0).id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedShoppingProducts_shouldRespectLimit() {
        UUID id1 = UUID.randomUUID();
        ProductEntity product1 = createProduct(id1, "Product 1", ProductStatusEn.ACTIVE);

        List<ProductEntity> products = List.of(product1);

        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn((List) products);
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);

        EntityManager em = mockEntityManagerForShoppingDto();
        when(productRepository.getEntityManager()).thenReturn(em);

        List<ProductShoppingListItemDto> result = featuredProductService.getFeaturedShoppingProducts(5, null);

        assertNotNull(result);
        // Verify the page was called with the correct limit
        verify(query).page(0, 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedShoppingProducts_shouldFilterByCategory() {
        UUID id1 = UUID.randomUUID();
        ProductEntity product1 = createProduct(id1, "Category Product", ProductStatusEn.ACTIVE);

        CategoryEntity category = new CategoryEntity();
        category.id = UUID.randomUUID();
        category.name = "Electronics";
        category.slug = "electronics";

        List<ProductEntity> products = List.of(product1);

        when(categoryRepository.findBySlugIgnoreCase("electronics")).thenReturn(category);

        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn((List) products);
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);

        EntityManager em = mockEntityManagerForShoppingDto();
        when(productRepository.getEntityManager()).thenReturn(em);

        List<ProductShoppingListItemDto> result = featuredProductService.getFeaturedShoppingProducts(null, "electronics");

        assertEquals(1, result.size());
        assertEquals("Category Product", result.get(0).name);
    }

    @Test
    void getFeaturedShoppingProducts_shouldReturnEmptyList_whenCategoryNotFound() {
        when(categoryRepository.findBySlugIgnoreCase("nonexistent")).thenReturn(null);

        List<ProductShoppingListItemDto> result = featuredProductService.getFeaturedShoppingProducts(null, "nonexistent");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedShoppingProducts_shouldDefaultLimitTo8_whenLimitIsNull() {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(Collections.emptyList());
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);

        featuredProductService.getFeaturedShoppingProducts(null, null);

        verify(query).page(0, 8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeaturedShoppingProducts_shouldCapLimitAt50() {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(Collections.emptyList());
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);

        featuredProductService.getFeaturedShoppingProducts(100, null);

        verify(query).page(0, 50);
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private ProductEntity createProduct(UUID id, String name, ProductStatusEn status) {
        ProductEntity product = new ProductEntity();
        product.id = id;
        product.name = name;
        product.slug = name.toLowerCase().replace(" ", "-");
        product.status = status;
        product.isFeatured = false;
        return product;
    }

    /**
     * Creates a mock EntityManager that returns empty results for the JPQL queries
     * used in toAdminProductListItemDto (variants, images, stock, prices).
     */
    @SuppressWarnings("unchecked")
    private EntityManager mockEntityManagerForAdminDto() {
        EntityManager em = mock(EntityManager.class);

        // Mock variant query (returns empty list)
        TypedQuery<ProductVariantEntity> variantQuery = mock(TypedQuery.class);
        when(variantQuery.setParameter(anyString(), any())).thenReturn(variantQuery);
        when(variantQuery.getResultList()).thenReturn(Collections.emptyList());
        when(em.createQuery(contains("ProductVariantEntity v WHERE v.product.id"), eq(ProductVariantEntity.class)))
                .thenReturn(variantQuery);

        // Mock stock count query
        TypedQuery<Long> stockQuery = mock(TypedQuery.class);
        when(stockQuery.setParameter(anyString(), any())).thenReturn(stockQuery);
        when(stockQuery.getSingleResult()).thenReturn(0L);
        when(em.createQuery(contains("SUM(v.stockQuantity)"), eq(Long.class)))
                .thenReturn(stockQuery);

        // Mock retail price query (returns empty list)
        TypedQuery<VariantPricesEntity> priceQuery = mock(TypedQuery.class);
        when(priceQuery.setParameter(anyString(), any())).thenReturn(priceQuery);
        when(priceQuery.setMaxResults(anyInt())).thenReturn(priceQuery);
        when(priceQuery.getResultList()).thenReturn(Collections.emptyList());
        when(em.createQuery(contains("VariantPricesEntity vp JOIN"), eq(VariantPricesEntity.class)))
                .thenReturn(priceQuery);

        return em;
    }

    /**
     * Creates a mock EntityManager that returns empty results for the JPQL queries
     * used in toShoppingListItemDto (variants, images, prices).
     */
    @SuppressWarnings("unchecked")
    private EntityManager mockEntityManagerForShoppingDto() {
        EntityManager em = mock(EntityManager.class);

        // Mock variant count query
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(em.createQuery(contains("COUNT(v)"), eq(Long.class))).thenReturn(countQuery);

        // Mock first variant id query
        TypedQuery<UUID> variantIdQuery = mock(TypedQuery.class);
        when(variantIdQuery.setParameter(anyString(), any())).thenReturn(variantIdQuery);
        when(variantIdQuery.setMaxResults(anyInt())).thenReturn(variantIdQuery);
        when(variantIdQuery.getResultList()).thenReturn(Collections.emptyList());
        when(em.createQuery(contains("SELECT v.id FROM ProductVariantEntity"), eq(UUID.class))).thenReturn(variantIdQuery);

        // Mock product images query
        TypedQuery<ProductImageEntity> imageQuery = mock(TypedQuery.class);
        when(imageQuery.setParameter(anyString(), any())).thenReturn(imageQuery);
        when(imageQuery.getResultList()).thenReturn(Collections.emptyList());
        when(em.createQuery(contains("ProductImageEntity pi"), eq(ProductImageEntity.class))).thenReturn(imageQuery);

        // Mock price queries (returns empty list)
        TypedQuery<VariantPricesEntity> priceQuery = mock(TypedQuery.class);
        when(priceQuery.setParameter(anyString(), any())).thenReturn(priceQuery);
        when(priceQuery.setMaxResults(anyInt())).thenReturn(priceQuery);
        when(priceQuery.getResultList()).thenReturn(Collections.emptyList());
        when(em.createQuery(contains("VariantPricesEntity vp JOIN"), eq(VariantPricesEntity.class))).thenReturn(priceQuery);

        return em;
    }
}
