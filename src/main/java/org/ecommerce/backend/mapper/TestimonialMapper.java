package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.TestimonialDto;
import org.ecommerce.common.dto.TestimonialPublicDto;
import org.ecommerce.common.entity.TestimonialEntity;
import org.mapstruct.Mapper;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps testimonials to the admin shape and the narrower public shape.
 * <p>
 * The public shape deliberately omits {@code published}, {@code sortOrder}, and the
 * timestamps — a storefront reader has no business seeing curation state.
 */
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface TestimonialMapper
{
    TestimonialDto toDto(TestimonialEntity entity);

    List<TestimonialDto> toDtos(List<TestimonialEntity> entities);

    TestimonialPublicDto toPublicDto(TestimonialEntity entity);

    List<TestimonialPublicDto> toPublicDtos(List<TestimonialEntity> entities);
}
