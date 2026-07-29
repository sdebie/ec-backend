package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.ecommerce.common.dto.CreateTestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.ecommerce.common.dto.TestimonialPublicDto;
import org.ecommerce.common.dto.UpdateTestimonialRequest;
import org.ecommerce.common.entity.TestimonialEntity;
import org.ecommerce.common.repository.TestimonialRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TestimonialService
{
    private static final Logger LOG = Logger.getLogger(TestimonialService.class);

    @Inject
    TestimonialRepository testimonialRepository;

    public List<TestimonialPublicDto> findPublished()
    {
        return testimonialRepository.findPublished()
                .stream()
                .map(this::toPublicDto)
                .toList();
    }

    public List<TestimonialDto> findAll()
    {
        return testimonialRepository.findAllOrdered()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public TestimonialDto getById(UUID id)
    {
        if (id == null) {
            return null;
        }
        TestimonialEntity entity = testimonialRepository.findById(id);
        if (entity == null) {
            return null;
        }
        return toDto(entity);
    }

    @Transactional
    public TestimonialDto create(CreateTestimonialRequest request)
    {
        TestimonialEntity entity = new TestimonialEntity();
        entity.setQuote(request.quote());
        entity.setAuthorName(request.authorName());
        entity.setAuthorTitle(request.authorTitle());
        entity.setPublished(request.published());
        entity.setSortOrder(request.sortOrder());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());

        testimonialRepository.persist(entity);
        LOG.infof("Testimonial created (id=%s, author=%s)", entity.getId(), entity.getAuthorName());

        return toDto(entity);
    }

    @Transactional
    public TestimonialDto update(UUID id, UpdateTestimonialRequest request)
    {
        if (id == null) {
            return null;
        }
        TestimonialEntity entity = testimonialRepository.findById(id);
        if (entity == null) {
            return null;
        }

        entity.setQuote(request.quote());
        entity.setAuthorName(request.authorName());
        entity.setAuthorTitle(request.authorTitle());
        entity.setPublished(request.published());
        entity.setSortOrder(request.sortOrder());
        entity.setUpdatedAt(OffsetDateTime.now());

        testimonialRepository.persist(entity);
        LOG.infof("Testimonial updated (id=%s)", entity.getId());

        return toDto(entity);
    }

    @Transactional
    public boolean delete(UUID id)
    {
        if (id == null) {
            return false;
        }
        TestimonialEntity entity = testimonialRepository.findById(id);
        if (entity == null) {
            return false;
        }
        testimonialRepository.delete(entity);
        LOG.infof("Testimonial deleted (id=%s)", id);
        return true;
    }

    private TestimonialDto toDto(TestimonialEntity entity)
    {
        return new TestimonialDto(
                entity.getId(),
                entity.getQuote(),
                entity.getAuthorName(),
                entity.getAuthorTitle(),
                entity.isPublished(),
                entity.getSortOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private TestimonialPublicDto toPublicDto(TestimonialEntity entity)
    {
        return new TestimonialPublicDto(
                entity.getId(),
                entity.getQuote(),
                entity.getAuthorName(),
                entity.getAuthorTitle()
        );
    }
}
