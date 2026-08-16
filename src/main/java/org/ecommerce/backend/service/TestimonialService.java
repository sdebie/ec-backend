package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.ecommerce.common.dto.TestimonialRequest;
import org.ecommerce.common.dto.TestimonialDto;
import org.ecommerce.common.dto.TestimonialPublicDto;
import org.ecommerce.backend.mapper.TestimonialMapper;
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

    @Inject
    TestimonialMapper testimonialMapper;

    public List<TestimonialPublicDto> findPublished()
    {
        return testimonialRepository.findPublished()
                .stream()
                .map(testimonialMapper::toPublicDto)
                .toList();
    }

    public List<TestimonialDto> findAll()
    {
        return testimonialRepository.findAllOrdered()
                .stream()
                .map(testimonialMapper::toDto)
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
        return testimonialMapper.toDto(entity);
    }

    @Transactional
    public TestimonialDto create(TestimonialRequest request)
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

        return testimonialMapper.toDto(entity);
    }

    @Transactional
    public TestimonialDto update(UUID id, TestimonialRequest request)
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

        return testimonialMapper.toDto(entity);
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

}
