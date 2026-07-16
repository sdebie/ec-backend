package org.ecommerce.backend.service;

// Feature: featured-products-list, Property 7: Limit Enforcement

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.entity.ProductEntity;
import org.ecommerce.common.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature: featured-products-list, Property 7: Limit Enforcement
 *
 * The REAL {@link FeaturedProductService#getFeaturedShoppingProducts} clamps the caller's
 * limit via resolveLimit before paging: null / &lt; 1 → 8, values in [1,50] → unchanged,
 * &gt; 50 → 50. Asserted by capturing the effective page size passed to the Panache query,
 * exercising the real service (not a re-implementation of resolveLimit).
 *
 * Exercises the real service via @QuarkusTest + PanacheMock (the query is mocked so the
 * page(offset, size) call can be verified). The property is quantified by iterating
 * representative limits in each clamp region.
 *
 * Validates: Requirements 4.2
 */
@QuarkusTest
class FeaturedLimitEnforcementPropertyTest {

    @Inject
    FeaturedProductService service;

    @InjectMock
    ProductRepository productRepository;

    private PanacheQuery<PanacheEntityBase> query;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PanacheMock.mock(ProductEntity.class);
        query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(Collections.emptyList());
        when(ProductEntity.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    /** Property: an explicit limit in [1,50] is passed through to page() unchanged. */
    @Test
    void validLimitIsUsedVerbatim() {
        for (int limit : new int[]{1, 2, 5, 8, 25, 49, 50}) {
            reset(query);
            when(query.page(anyInt(), anyInt())).thenReturn(query);
            when(query.list()).thenReturn(Collections.emptyList());

            service.getFeaturedShoppingProducts(limit, null);
            verify(query).page(0, limit);
        }
    }

    /** Property: any limit above the cap is clamped to 50. */
    @Test
    void limitAboveCapIsClampedToFifty() {
        for (int limit : new int[]{51, 100, 1000, 10_000}) {
            reset(query);
            when(query.page(anyInt(), anyInt())).thenReturn(query);
            when(query.list()).thenReturn(Collections.emptyList());

            service.getFeaturedShoppingProducts(limit, null);
            verify(query).page(0, FeaturedProductService.FEATURED_CAP);
        }
    }

    /** Property: any limit below 1, and null, falls back to the default of 8. */
    @Test
    void invalidOrNullLimitFallsBackToDefaultEight() {
        Integer[] limits = {0, -1, -100, null};
        for (Integer limit : limits) {
            reset(query);
            when(query.page(anyInt(), anyInt())).thenReturn(query);
            when(query.list()).thenReturn(Collections.emptyList());

            service.getFeaturedShoppingProducts(limit, null);
            verify(query).page(0, 8);
        }
    }
}
