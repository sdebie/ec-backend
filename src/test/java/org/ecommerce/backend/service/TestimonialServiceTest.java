package org.ecommerce.backend.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.CreateTestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.ecommerce.common.dto.TestimonialPublicDto;
import org.ecommerce.common.entity.TestimonialEntity;
import org.ecommerce.common.repository.TestimonialRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * DB-backed correctness tests for TestimonialService.
 * These call the REAL service (not a re-implementation) and verify Properties 1–3.
 * <p>
 */
@QuarkusTest
class TestimonialServiceTest
{
    @Inject
    TestimonialService testimonialService;

    @Inject
    TestimonialRepository testimonialRepository;

    /**
     * Property 1: Public listing returns only published testimonials in sort_order ASC.
     * Persists a mix of published/unpublished with varying sort orders; verifies
     * the service returns only published ones in the correct order with the public shape.
     * <p>
     */
    @Test
    @TestTransaction
    void findPublished_returnsOnlyPublishedInSortOrder()
    {
        clearTestimonialsWithinTestTransaction();
        persist("Published B", "Author B", true, 2);
        persist("Unpublished", "Author U", false, 0);
        persist("Published A", "Author A", true, 1);
        persist("Published C", "Author C", true, 3);

        List<TestimonialPublicDto> result = testimonialService.findPublished();

        // Only published (3 of 4)
        assertThat(result, hasSize(3));

        // Ordered by sortOrder ASC
        assertThat(result.get(0).quote(), is("Published A"));
        assertThat(result.get(1).quote(), is("Published B"));
        assertThat(result.get(2).quote(), is("Published C"));

        // Public shape: has id, authorName, authorTitle — no published/sortOrder/timestamps
        assertThat(result.get(0).id(), notNullValue());
        assertThat(result.get(0).authorName(), is("Author A"));
    }

    /**
     * Property 2: Admin listing returns all testimonials ordered by sortOrder ASC, createdAt DESC.
     * <p>
     */
    @Test
    @TestTransaction
    void findAll_returnsAllTestimonialsOrdered()
    {
        clearTestimonialsWithinTestTransaction();
        persist("Quote 1", "Author 1", true, 1);
        persist("Quote 2", "Author 2", false, 1); // same sortOrder, later createdAt → first in desc
        persist("Quote 3", "Author 3", true, 0);  // lower sortOrder → first overall

        List<TestimonialDto> result = testimonialService.findAll();

        // All 3 returned
        assertThat(result, hasSize(3));

        // sortOrder 0 first
        assertThat(result.get(0).quote(), is("Quote 3"));
        assertThat(result.get(0).sortOrder(), is(0));

        // sortOrder 1 — two items; within same sortOrder, createdAt DESC (Quote 2 persisted after Quote 1)
        assertThat(result.get(1).sortOrder(), is(1));
        assertThat(result.get(2).sortOrder(), is(1));
    }

    /**
     * Property 3: Create–read round trip preserves all fields.
     * <p>
     */
    @Test
    @TestTransaction
    void create_thenGetById_roundTrip()
    {
        clearTestimonialsWithinTestTransaction();
        CreateTestimonialRequest request = new CreateTestimonialRequest(
                "Great service!", "Jane Doe", "CEO, Acme Corp", 5, true
        );

        TestimonialDto created = testimonialService.create(request);

        assertThat(created.id(), notNullValue());
        assertThat(created.quote(), is("Great service!"));
        assertThat(created.authorName(), is("Jane Doe"));
        assertThat(created.authorTitle(), is("CEO, Acme Corp"));
        assertThat(created.published(), is(true));
        assertThat(created.sortOrder(), is(5));
        assertThat(created.createdAt(), notNullValue());
        assertThat(created.updatedAt(), notNullValue());

        // Read back via the real service
        TestimonialDto readBack = testimonialService.getById(created.id());
        assertThat(readBack, notNullValue());
        assertThat(readBack.quote(), is("Great service!"));
        assertThat(readBack.authorName(), is("Jane Doe"));
        assertThat(readBack.authorTitle(), is("CEO, Acme Corp"));
        assertThat(readBack.published(), is(true));
        assertThat(readBack.sortOrder(), is(5));
    }

    /**
     * Property 1 edge case: empty DB returns empty list (not null, not error).
     * <p>
     */
    @Test
    @TestTransaction
    void findPublished_emptyDb_returnsEmptyList()
    {
        clearTestimonialsWithinTestTransaction();
        List<TestimonialPublicDto> result = testimonialService.findPublished();
        assertThat(result, is(empty()));
    }

    /**
     * The test suite shares the local development database, which may contain operator-created
     * testimonials. The enclosing {@link TestTransaction} rolls this delete back after each
     * test, so fixtures can assert an exact collection without mutating that data.
     */
    private void clearTestimonialsWithinTestTransaction()
    {
        testimonialRepository.deleteAll();
    }

    private void persist(String quote, String authorName, boolean published, int sortOrder)
    {
        TestimonialEntity entity = new TestimonialEntity();
        entity.setQuote(quote);
        entity.setAuthorName(authorName);
        entity.setAuthorTitle(null);
        entity.setPublished(published);
        entity.setSortOrder(sortOrder);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        testimonialRepository.persist(entity);
    }
}
