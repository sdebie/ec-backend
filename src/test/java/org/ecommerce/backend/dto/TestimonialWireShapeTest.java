package org.ecommerce.backend.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.ecommerce.common.dto.CreateTestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Wire-shape test: pins the JSON field as "published" (NOT "isPublished").
 * Guards the isX boolean record-component naming decision.
 *
 */
class TestimonialWireShapeTest
{
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp()
    {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void testimonialDto_serializes_published_notIsPublished() throws Exception
    {
        var dto = new TestimonialDto(
                UUID.randomUUID(), "Test", "Author", "Title",
                true, 1, OffsetDateTime.now(), OffsetDateTime.now()
        );
        String json = objectMapper.writeValueAsString(dto);
        assertThat(json, containsString("\"published\":true"));
        assertThat(json, not(containsString("\"isPublished\"")));
    }

    @Test
    void testimonialDto_serializes_published_false() throws Exception
    {
        var dto = new TestimonialDto(
                UUID.randomUUID(), "Quote", "Author", null,
                false, 0, OffsetDateTime.now(), OffsetDateTime.now()
        );
        String json = objectMapper.writeValueAsString(dto);
        assertThat(json, containsString("\"published\":false"));
        assertThat(json, not(containsString("\"isPublished\"")));
    }

    @Test
    void createTestimonialRequest_deserializes_published_field() throws Exception
    {
        String json = """
                {"quote":"Test","authorName":"A","authorTitle":null,"sortOrder":0,"published":true}
                """;
        CreateTestimonialRequest request = objectMapper.readValue(json, CreateTestimonialRequest.class);
        assertThat(request.published(), is(true));
        assertThat(request.quote(), is("Test"));
        assertThat(request.authorName(), is("A"));
    }

    @Test
    void createTestimonialRequest_deserializes_published_false() throws Exception
    {
        String json = """
                {"quote":"Hello","authorName":"Bob","authorTitle":"CEO","sortOrder":3,"published":false}
                """;
        CreateTestimonialRequest request = objectMapper.readValue(json, CreateTestimonialRequest.class);
        assertThat(request.published(), is(false));
        assertThat(request.quote(), is("Hello"));
        assertThat(request.authorTitle(), is("CEO"));
        assertThat(request.sortOrder(), is(3));
    }
}
