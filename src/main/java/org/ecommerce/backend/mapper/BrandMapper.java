package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.entity.BrandEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface BrandMapper
{
    BrandDto mapEntityToDto(BrandEntity brandEntity);

    List<BrandDto> mapEntityToDto(List<BrandEntity> allBrands);

    BrandEntity mapDtoToEntity(BrandDto brandDto, @MappingTarget BrandEntity brandEntity);
}
