package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 1: Featured Toggle Round-Trip

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
 * Feature: featured-products-list, Property 1: Featured Toggle Round-Trip
 * <p>
 * For any product that is not featured, setFeatured(id, true) then setFeatured(id, false)
 * on the REAL {@link FeaturedProductService} leaves is_featured = false, and each step's
 * DTO reflects the new state.
 * <p>
 * Exercises the real service via @QuarkusTest + PanacheMock. The property is quantified by
 * iterating every product status and a range of pre-existing featured counts (kept below
 * the cap so the featuring step succeeds).
 * <p>
 */
@QuarkusTest
class FeaturedToggleRoundTripPropertyTest
{
    @Inject
    FeaturedProductService service;

    @InjectMock
    ProductRepository productRepository;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(ProductEntity.class);
    }

    /**
     * Property: true-then-false leaves the product not featured, for any status / below-cap count.
     */
    @Test
    void toggleTrueThenFalseLeavesProductNotFeatured()
    {
        long[] existingCounts = {0L, 1L, 25L, 49L};
        for (ProductStatusEn status : ProductStatusEn.values()) {
            for (long existingCount : existingCounts) {
                UUID productId = UUID.randomUUID();
                ProductEntity product = spy(product(productId, "Toggle Product", status));
                doNothing().when(product).persist();
                when(productRepository.findById(productId)).thenReturn(product);
                // Count is only read on the featuring step; keep below cap.
                when(ProductEntity.count("isFeatured", true)).thenReturn(existingCount);

                assertFalse(product.isFeatured(), "Precondition: product starts not featured");

                FeaturedProductResultDto featured = service.setFeatured(productId, true);
                assertTrue(featured.isFeatured(), "setFeatured(true) should report featured");
                assertTrue(product.isFeatured(), "Product should be featured after step 1");

                FeaturedProductResultDto unfeatured = service.setFeatured(productId, false);
                assertFalse(unfeatured.isFeatured(), "setFeatured(false) should report not featured");
                assertFalse(product.isFeatured(), "Product must be not featured after round-trip");
                assertEquals(productId.toString(), unfeatured.getProductId());
            }
        }
    }

    private ProductEntity product(UUID id, String name, ProductStatusEn status)
    {
        ProductEntity product = new ProductEntity();
        product.setId(id);
        product.setName(name);
        product.setSlug("p-" + id);
        product.setStatus(status);
        product.setFeatured(false);
        return product;
    }
}
