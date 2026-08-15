package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.ReportingPolicy.ERROR;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "jakarta-cdi", unmappedTargetPolicy = ERROR, nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface CategoryMapper
{
    CategoryDto mapEntityToDto(CategoryEntity categoryEntity);

    List<CategoryDto> mapEntityToDto(List<CategoryEntity> categoryEntities);

    // A category's products are owned by the product side of the relationship; a category
    // write never reassigns them.
    @Mapping(target = "products", ignore = true)
    CategoryEntity mapDtoToEntity(CategoryDto categoryDto);

    /**
     * {@code parent} is deliberately not mapped: on a {@code @MappingTarget} overload MapStruct
     * writes a nested association INTO the existing associated entity rather than replacing the
     * reference, so an id-only parent stub would null the live parent row's fields at flush.
     * {@link org.ecommerce.backend.service.CategoryService} resolves the parent by id instead.
     */
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "products", ignore = true)
    CategoryEntity mapDtoToEntity(CategoryDto categoryDto, @MappingTarget CategoryEntity categoryEntity);

}
