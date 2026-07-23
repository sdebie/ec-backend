package org.ecommerce.backend.api.rest;

import org.ecommerce.backend.service.TestimonialService;
import org.ecommerce.common.dto.TestimonialPublicDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the public storefront testimonial endpoint.
 * Verifies response codes and DTO shape without Quarkus runtime.
 *
 * Validates: Requirements 2.1, 2.2
 */
class StorefrontTestimonialResourceTest
{
    @Mock
    TestimonialService testimonialService;

    @InjectMocks
    StorefrontTestimonialResource resource;

    @BeforeEach
    void setUp() { MockitoAnnotations.openMocks(this); }

    @Test
    void list_returnsEmptyArray_whenNonePublished()
    {
        when(testimonialService.findPublished()).thenReturn(List.of());
        Response response = resource.list();
        assertThat(response.getStatus(), is(200));
        assertThat(response.getEntity(), is(List.of()));
    }

    @Test
    void list_returnsPublicDtoShape()
    {
        var dto = new TestimonialPublicDto(UUID.randomUUID(), "Quote", "Author", "Title");
        when(testimonialService.findPublished()).thenReturn(List.of(dto));
        Response response = resource.list();
        assertThat(response.getStatus(), is(200));
        @SuppressWarnings("unchecked")
        List<TestimonialPublicDto> result = (List<TestimonialPublicDto>) response.getEntity();
        assertThat(result, hasSize(1));
        assertThat(result.get(0).quote(), is("Quote"));
        assertThat(result.get(0).authorName(), is("Author"));
        assertThat(result.get(0).authorTitle(), is("Title"));
    }
}
