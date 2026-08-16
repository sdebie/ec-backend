package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 4: Status-Independent Toggle

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Feature: featured-products-list, Property 4: Status-Independent Toggle
 * <p>
 * For a product of ANY status (ACTIVE, PENDING, DISABLED), setFeatured(id, true) on the
 * REAL {@link FeaturedProductService} succeeds when below the cap — the product's status
 * never gates featuring, and featuring never mutates the status.
 * <p>
 * Exercises the real service via @QuarkusTest + PanacheMock; the property is quantified by
 * iterating every status against a range of below-cap counts.
 * <p>
 */
@QuarkusTest
class FeaturedStatusIndependentTogglePropertyTest
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
     * Property: featuring succeeds for every status when the cap is not reached.
     */
    @Test
    void setFeaturedSucceedsForAnyStatus()
    {
        long[] belowCap = {0L, 1L, 30L, 49L};
        for (ProductStatusEn status : ProductStatusEn.values()) {
            for (long count : belowCap) {
                UUID productId = UUID.randomUUID();
                ProductEntity product = spy(product(productId, "Status Product", status));
                doNothing().when(product).persist();
                when(productRepository.findById(productId)).thenReturn(product);
                when(ProductEntity.count("isFeatured", true)).thenReturn(count);

                FeaturedProductResultDto result = service.setFeatured(productId, true);

                assertTrue(result.isFeatured(), "Featuring must succeed regardless of status (" + status + ", count=" + count + ")");
                assertTrue(product.isFeatured(), "Product should be featured after toggle");
                assertEquals(status, product.getStatus(), "setFeatured must not alter product status");
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
