package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 2: Cap Enforcement

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.exception.FeaturedCapExceededException;
import org.ecommerce.common.dto.FeaturedProductResultDto;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Feature: featured-products-list, Property 2: Cap Enforcement
 *
 * For any current featured count at or above the cap of 50, setFeatured(id, true) on the
 * REAL {@link FeaturedProductService} is rejected with {@link FeaturedCapExceededException}
 * and the product's is_featured flag is left unchanged; below the cap it succeeds.
 *
 * Exercises the real service via @QuarkusTest + @Inject, with @InjectMock
 * {@link ProductRepository} and {@code PanacheMock.mock(ProductEntity.class)} for the
 * static {@code count} call (the supported way to mock Panache statics — mirrors
 * FeaturedProductServiceTest). jqwik cannot be used here because its engine is
 * incompatible with @QuarkusTest CDI / PanacheMock; the property is quantified by
 * iterating representative counts and every product status.
 *
 * Validates: Requirements 1.3, 2.4
 */
@QuarkusTest
class FeaturedCapEnforcementPropertyTest {

    @Inject
    FeaturedProductService service;

    @InjectMock
    ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        PanacheMock.mock(ProductEntity.class);
    }

    /** Property: at/over cap → rejected, no state change; for every count boundary and status. */
    @Test
    void featuringAtOrOverCapIsRejectedForEveryStatus() {
        long[] atOrOverCap = {FeaturedProductService.FEATURED_CAP, 51L, 99L, 500L};
        for (ProductStatusEn status : ProductStatusEn.values()) {
            for (long count : atOrOverCap) {
                UUID productId = UUID.randomUUID();
                ProductEntity product = spy(product(productId, "Cap Product", status));
                doNothing().when(product).persist();
                when(productRepository.findById(productId)).thenReturn(product);
                when(ProductEntity.count("isFeatured", true)).thenReturn(count);

                assertThrows(FeaturedCapExceededException.class,
                        () -> service.setFeatured(productId, true),
                        "count=" + count + " status=" + status + " must be rejected");

                assertFalse(product.isFeatured,
                        "Product must remain not-featured after cap rejection (count=" + count + ")");
            }
        }
    }

    /** Complement: strictly below cap → succeeds, flag set true; confirms boundary is exactly 50. */
    @Test
    void featuringBelowCapSucceedsForEveryStatus() {
        long[] belowCap = {0L, 1L, 49L};
        for (ProductStatusEn status : ProductStatusEn.values()) {
            for (long count : belowCap) {
                UUID productId = UUID.randomUUID();
                ProductEntity product = spy(product(productId, "Below Cap Product", status));
                doNothing().when(product).persist();
                when(productRepository.findById(productId)).thenReturn(product);
                when(ProductEntity.count("isFeatured", true)).thenReturn(count);

                FeaturedProductResultDto result = service.setFeatured(productId, true);

                assertTrue(result.featured, "count=" + count + " status=" + status + " should succeed");
                assertTrue(product.isFeatured, "Product should be featured when below cap");
            }
        }
    }

    private ProductEntity product(UUID id, String name, ProductStatusEn status) {
        ProductEntity product = new ProductEntity();
        product.id = id;
        product.name = name;
        product.slug = name.toLowerCase().replace(" ", "-") + "-" + id;
        product.status = status;
        product.isFeatured = false;
        return product;
    }
}
