package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 3: Not-Found Rejection

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Feature: featured-products-list, Property 3: Not-Found Rejection
 * <p>
 * For any UUID that does not correspond to an existing product, setFeatured(uuid, true)
 * or setFeatured(uuid, false) on the REAL {@link FeaturedProductService} throws
 * {@link NotFoundException} (referencing the id) before any mutation — the featured count
 * is never consulted.
 * <p>
 * Exercises the real service via @QuarkusTest with @InjectMock {@link ProductRepository}
 * returning null. The property is quantified by iterating many random ids and both
 * boolean flag values.
 * <p>
 */
@QuarkusTest
class FeaturedNotFoundPropertyTest
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
     * Property: for any missing id and either flag, setFeatured throws NotFound and never reads the cap count.
     */
    @Test
    void setFeaturedWithMissingIdThrowsNotFoundRegardlessOfFlag()
    {
        for (int i = 0; i < 200; i++) {
            UUID missingId = UUID.randomUUID();
            boolean featured = (i % 2 == 0);
            when(productRepository.findById(missingId)).thenReturn(null);

            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.setFeatured(missingId, featured),
                    "Missing id must be rejected (featured=" + featured + ")");

            assertTrue(ex.getMessage().contains(missingId.toString()),
                    "Error message should reference the missing product id");
        }

        // The mutation path is never entered, so the cap count is never queried.
        PanacheMock.verifyNoInteractions(ProductEntity.class);
    }
}
