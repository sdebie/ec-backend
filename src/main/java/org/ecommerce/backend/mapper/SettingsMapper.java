package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.dto.StoreSettingsDto;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface SettingsMapper {
    StoreSettingsDto mapStoreSettingsEntityToDto(StoreSettingsEntity entity);

    List<StoreSettingsDto> mapStoreSettingsEntityToDtoList(List<StoreSettingsEntity> entities);

    StoreSettingsEntity mapStoreSettingsDtoToEntity(StoreSettingsDto dto, @MappingTarget StoreSettingsEntity entity);

    ShippingMethodDto mapShippingMethodEntityToDto(ShippingMethodEntity entity);

    List<ShippingMethodDto> mapShippingMethodEntityToDtoList(List<ShippingMethodEntity> entities);

    ShippingMethodEntity mapShippingMethodDtoToEntity(ShippingMethodDto dto, @MappingTarget ShippingMethodEntity entity);
}
