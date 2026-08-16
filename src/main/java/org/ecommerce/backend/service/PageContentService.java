package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.common.dto.PageContentDto;
import org.ecommerce.common.dto.PageContentSummaryDto;
import org.ecommerce.backend.mapper.PageContentMapper;
import org.ecommerce.common.entity.PageContentEntity;
import org.ecommerce.common.repository.PageContentRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class PageContentService
{
    @Inject
    PageContentRepository pageContentRepository;

    @Inject
    PageContentMapper pageContentMapper;

    @Inject
    HtmlSanitizer htmlSanitizer;

    /**
     * Lists all pages in the given category as summary DTOs (no content fields).
     * {@code hasUnpublishedChanges} is true when {@code draftContent} differs from {@code publishedContent}.
     */
    public List<PageContentSummaryDto> listByCategory(String category)
    {
        List<PageContentEntity> entities = pageContentRepository.findByCategory(category);
        return entities
                .stream()
                .map(pageContentMapper::toSummaryDto)
                .toList();
    }

    /**
     * Returns the full page content by id, or null if not found.
     */
    public PageContentDto getById(UUID id)
    {
        if (id == null) {
            return null;
        }

        PageContentEntity entity = pageContentRepository.findById(id);
        if (entity == null) {
            return null;
        }

        return pageContentMapper.toDto(entity);
    }

    /**
     * Returns the published content for a public-facing page by slug.
     * Returns null if the page does not exist or has never been published (publishedAt is null).
     * The returned shape is: slug, title, content (= publishedContent), publishedAt.
     */
    public PageContentDto getPublishedBySlug(String slug)
    {
        PageContentEntity entity = pageContentRepository.findBySlug(slug);
        if (entity == null || entity.getPublishedAt() == null) {
            return null;
        }

        return new PageContentDto(
                entity.getId(),
                entity.getSlug(),
                entity.getTitle(),
                entity.getCategory(),
                null,
                entity.getPublishedContent(),
                entity.getPublishedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Saves a draft: sanitises the content, writes it to {@code draftContent} and updates
     * {@code updatedAt}. Does NOT touch {@code publishedContent} or {@code publishedAt}.
     *
     * @return the updated DTO, or null if the page is not found
     */
    @Transactional
    public PageContentDto saveDraft(UUID id, String content)
    {
        if (id == null) {
            log.warn("saveDraft called with null id");
            return null;
        }

        PageContentEntity entity = pageContentRepository.findById(id);
        if (entity == null) {
            log.warn("saveDraft: page with id {} not found", id);
            return null;
        }

        String sanitisedContent = htmlSanitizer.sanitize(content);
        entity.setDraftContent(sanitisedContent);
        entity.setUpdatedAt(OffsetDateTime.now());

        pageContentRepository.persist(entity);
        log.info("Draft saved for page '{}' (id={})", entity.getTitle(), entity.getId());

        return pageContentMapper.toDto(entity);
    }

    /**
     * Publishes a page: copies {@code draftContent} → {@code publishedContent},
     * sets {@code publishedAt} and {@code updatedAt} to now.
     *
     * @return the updated DTO, or null if the page is not found
     */
    @Transactional
    public PageContentDto publish(UUID id)
    {
        if (id == null) {
            log.warn("publish called with null id");
            return null;
        }

        PageContentEntity entity = pageContentRepository.findById(id);
        if (entity == null) {
            log.warn("publish: page with id {} not found", id);
            return null;
        }

        entity.setPublishedContent(entity.getDraftContent());
        entity.setPublishedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        pageContentRepository.persist(entity);
        log.info("Page '{}' (id={}) published", entity.getTitle(), entity.getId());

        return pageContentMapper.toDto(entity);
    }

}
