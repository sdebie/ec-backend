package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface CategoryMapper
{
    CategoryDto mapEntityToDto(CategoryEntity categoryEntity);

    List<CategoryDto> mapEntityToDto(List<CategoryEntity> categoryEntities);

    CategoryEntity mapDtoToEntity(CategoryDto categoryDto);

    CategoryEntity mapDtoToEntity(CategoryDto categoryDto, @MappingTarget CategoryEntity categoryEntity);

}
