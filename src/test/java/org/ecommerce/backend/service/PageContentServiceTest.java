package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.PageContentDto;
import org.ecommerce.common.entity.PageContentEntity;
import org.ecommerce.common.repository.PageContentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PageContentService}.
 * Uses Mockito mocking for PageContentRepository and HtmlSanitizer.
 * <p>
 * Validates: Requirements 3.3, 3.4, 3.7, 5.5
 */
@QuarkusTest
class PageContentServiceTest
{
    @Inject
    PageContentService pageContentService;

    @InjectMock
    PageContentRepository pageContentRepository;

    @InjectMock
    HtmlSanitizer htmlSanitizer;

    @Test
    void saveDraft_shouldWriteDraftContentAndLeavePublishedUnchanged()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "privacy-policy", "Privacy Policy");
        entity.setPublishedContent("<p>Old published</p>");
        entity.setPublishedAt(OffsetDateTime.now().minusDays(5));
        OffsetDateTime originalPublishedAt = entity.getPublishedAt();

        when(pageContentRepository.findById(id)).thenReturn(entity);
        when(htmlSanitizer.sanitize(anyString())).thenReturn("<p>New draft</p>");

        PageContentDto result = pageContentService.saveDraft(id, "<p>New draft</p>");

        assertNotNull(result);
        assertEquals("<p>New draft</p>", result.draftContent());
        assertEquals("<p>Old published</p>", result.publishedContent());
        assertEquals(originalPublishedAt, result.publishedAt());
        verify(pageContentRepository).persist(entity);
        verify(htmlSanitizer).sanitize("<p>New draft</p>");
    }

    @Test
    void saveDraft_shouldCallSanitizerBeforePersisting()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "terms", "Terms");

        when(pageContentRepository.findById(id)).thenReturn(entity);
        when(htmlSanitizer.sanitize(anyString())).thenReturn("<p>sanitised</p>");

        pageContentService.saveDraft(id, "<script>alert('xss')</script><p>sanitised</p>");

        assertEquals("<p>sanitised</p>", entity.getDraftContent());
        verify(htmlSanitizer).sanitize("<script>alert('xss')</script><p>sanitised</p>");
    }

    @Test
    void saveDraft_shouldUpdateUpdatedAtTimestamp()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "terms", "Terms");
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        when(pageContentRepository.findById(id)).thenReturn(entity);
        when(htmlSanitizer.sanitize(anyString())).thenReturn("<p>content</p>");

        pageContentService.saveDraft(id, "<p>content</p>");

        assertTrue(entity.getUpdatedAt().isAfter(before));
    }

    @Test
    void saveDraft_shouldNotModifyPublishedContentOrPublishedAt()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "terms", "Terms");
        entity.setPublishedContent("<p>Published version</p>");
        entity.setPublishedAt(OffsetDateTime.now().minusDays(10));
        String originalPublished = entity.getPublishedContent();
        OffsetDateTime originalPublishedAt = entity.getPublishedAt();

        when(pageContentRepository.findById(id)).thenReturn(entity);
        when(htmlSanitizer.sanitize(anyString())).thenReturn("<p>Draft version</p>");

        pageContentService.saveDraft(id, "<p>Draft version</p>");

        assertEquals(originalPublished, entity.getPublishedContent());
        assertEquals(originalPublishedAt, entity.getPublishedAt());
    }

    @Test
    void publish_shouldCopyDraftToPublishedAndSetPublishedAt()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "terms", "Terms");
        entity.setDraftContent("<h2>Draft heading</h2><p>Content</p>");
        entity.setPublishedContent(null);
        entity.setPublishedAt(null);

        when(pageContentRepository.findById(id)).thenReturn(entity);

        PageContentDto result = pageContentService.publish(id);

        assertNotNull(result);
        assertEquals("<h2>Draft heading</h2><p>Content</p>", result.publishedContent());
        assertNotNull(result.publishedAt());
        assertEquals(entity.getDraftContent(), entity.getPublishedContent());
        verify(pageContentRepository).persist(entity);
    }

    @Test
    void publish_shouldSetUpdatedAt()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "terms", "Terms");
        entity.setDraftContent("<p>content</p>");
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);

        when(pageContentRepository.findById(id)).thenReturn(entity);

        pageContentService.publish(id);

        assertTrue(entity.getUpdatedAt().isAfter(before));
    }

    @Test
    void afterPublishThenSaveDraft_getPublishedBySlug_shouldReturnPreviouslyPublishedContent()
    {
        UUID id = UUID.randomUUID();
        PageContentEntity entity = createEntity(id, "privacy-policy", "Privacy Policy");
        entity.setDraftContent("<p>Version 1</p>");
        entity.setPublishedContent(null);
        entity.setPublishedAt(null);

        when(pageContentRepository.findById(id)).thenReturn(entity);

        // Publish: copies draftContent → publishedContent
        pageContentService.publish(id);
        assertEquals("<p>Version 1</p>", entity.getPublishedContent());
        assertNotNull(entity.getPublishedAt());

        // Save a new draft (edit-after-publish)
        when(htmlSanitizer.sanitize(anyString())).thenReturn("<p>Version 2 draft</p>");
        pageContentService.saveDraft(id, "<p>Version 2 draft</p>");

        // Verify the entity now has the new draft but the published content is unchanged
        assertEquals("<p>Version 2 draft</p>", entity.getDraftContent());
        assertEquals("<p>Version 1</p>", entity.getPublishedContent());

        // Now test getPublishedBySlug returns the published version
        when(pageContentRepository.findBySlug("privacy-policy")).thenReturn(entity);
        PageContentDto publicResult = pageContentService.getPublishedBySlug("privacy-policy");

        assertNotNull(publicResult);
        assertEquals("<p>Version 1</p>", publicResult.publishedContent());
    }

    @Test
    void getById_withNonExistentId_shouldReturnNull()
    {
        UUID id = UUID.randomUUID();
        when(pageContentRepository.findById(id)).thenReturn(null);

        PageContentDto result = pageContentService.getById(id);

        assertNull(result);
    }

    @Test
    void getById_withNullId_shouldReturnNull()
    {
        PageContentDto result = pageContentService.getById(null);

        assertNull(result);
    }

    @Test
    void getPublishedBySlug_withUnpublishedPage_shouldReturnNull()
    {
        PageContentEntity entity = createEntity(UUID.randomUUID(), "terms", "Terms");
        entity.setPublishedAt(null);
        entity.setPublishedContent(null);

        when(pageContentRepository.findBySlug("terms")).thenReturn(entity);

        PageContentDto result = pageContentService.getPublishedBySlug("terms");

        assertNull(result);
    }

    @Test
    void getPublishedBySlug_withNonExistentSlug_shouldReturnNull()
    {
        when(pageContentRepository.findBySlug("non-existent")).thenReturn(null);

        PageContentDto result = pageContentService.getPublishedBySlug("non-existent");

        assertNull(result);
    }

    private PageContentEntity createEntity(UUID id, String slug, String title)
    {
        PageContentEntity entity = new PageContentEntity();
        entity.setId(id);
        entity.setSlug(slug);
        entity.setTitle(title);
        entity.setCategory("LEGAL");
        entity.setDraftContent(null);
        entity.setPublishedContent(null);
        entity.setPublishedAt(null);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}
