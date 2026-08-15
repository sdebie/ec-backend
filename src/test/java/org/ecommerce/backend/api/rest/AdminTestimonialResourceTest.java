package org.ecommerce.backend.api.rest;

import org.ecommerce.backend.service.TestimonialService;
import org.ecommerce.common.dto.TestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin testimonial CRUD endpoint.
 * Verifies response codes, 404 for missing, and DTO pass-through.
 * Role enforcement (403) is tested in the integration test since @RolesAllowed
 * requires the JAX-RS security interceptor.
 *
 */
class AdminTestimonialResourceTest
{
    @Mock
    TestimonialService testimonialService;

    @InjectMocks
    AdminTestimonialResource resource;

    @BeforeEach
    void setUp() { MockitoAnnotations.openMocks(this); }

    @Test
    void list_returns200_withAllTestimonials()
    {
        var dto = makeDto();
        when(testimonialService.findAll()).thenReturn(List.of(dto));
        Response response = resource.list();
        assertThat(response.getStatus(), is(200));
        @SuppressWarnings("unchecked")
        List<TestimonialDto> result = (List<TestimonialDto>) response.getEntity();
        assertThat(result, hasSize(1));
        assertThat(result.get(0).quote(), is("Test quote"));
    }

    @Test
    void getById_returns404_whenNotFound()
    {
        UUID id = UUID.randomUUID();
        when(testimonialService.getById(id)).thenReturn(null);
        Response response = resource.getById(id);
        assertThat(response.getStatus(), is(404));
    }

    @Test
    void getById_returns200_whenFound()
    {
        var dto = makeDto();
        when(testimonialService.getById(dto.id())).thenReturn(dto);
        Response response = resource.getById(dto.id());
        assertThat(response.getStatus(), is(200));
        assertThat(response.getEntity(), is(dto));
    }

    @Test
    void create_returns201()
    {
        var request = new TestimonialRequest("Quote", "Author", null, 0, true);
        var dto = makeDto();
        when(testimonialService.create(request)).thenReturn(dto);
        Response response = resource.create(request);
        assertThat(response.getStatus(), is(201));
        assertThat(response.getEntity(), is(dto));
    }

    @Test
    void update_returns404_whenNotFound()
    {
        UUID id = UUID.randomUUID();
        var request = new TestimonialRequest("Q", "A", null, 0, false);
        when(testimonialService.update(eq(id), any())).thenReturn(null);
        Response response = resource.update(id, request);
        assertThat(response.getStatus(), is(404));
    }

    @Test
    void update_returns200_whenFound()
    {
        var dto = makeDto();
        var request = new TestimonialRequest("Updated", "Author", null, 1, true);
        when(testimonialService.update(eq(dto.id()), any())).thenReturn(dto);
        Response response = resource.update(dto.id(), request);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getEntity(), is(dto));
    }

    @Test
    void delete_returns404_whenNotFound()
    {
        UUID id = UUID.randomUUID();
        when(testimonialService.delete(id)).thenReturn(false);
        Response response = resource.delete(id);
        assertThat(response.getStatus(), is(404));
    }

    @Test
    void delete_returns204_whenDeleted()
    {
        UUID id = UUID.randomUUID();
        when(testimonialService.delete(id)).thenReturn(true);
        Response response = resource.delete(id);
        assertThat(response.getStatus(), is(204));
    }

    private TestimonialDto makeDto()
    {
        return new TestimonialDto(
                UUID.randomUUID(), "Test quote", "Author Name", "Title",
                true, 0, OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
