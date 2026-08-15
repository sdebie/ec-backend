package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.PageContentDto;
import org.ecommerce.common.dto.PageContentSummaryDto;
import org.ecommerce.common.entity.PageContentEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Maps content pages to the full editor shape and the list summary.
 * <p>
 * The summary carries {@code hasUnpublishedChanges} so the list can flag pages whose draft
 * has moved ahead of what readers see, without shipping both bodies to render a list.
 */
@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR,
        nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface PageContentMapper
{
    PageContentDto toDto(PageContentEntity entity);

    @Mapping(target = "hasUnpublishedChanges", expression = "java(entity.hasUnpublishedChanges())")
    PageContentSummaryDto toSummaryDto(PageContentEntity entity);

    List<PageContentSummaryDto> toSummaryDtos(List<PageContentEntity> entities);
}
