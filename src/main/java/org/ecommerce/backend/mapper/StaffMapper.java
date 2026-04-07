package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.StaffDto;
import org.ecommerce.common.entity.StaffUserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

@Mapper(componentModel = "cdi", nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface StaffMapper
{
    StaffDto mapEntityToDto(StaffUserEntity staffUser);

    List<StaffDto> mapEntityToDto(List<StaffUserEntity> allStaffUsers);

    StaffUserEntity mapDtoToEntity(StaffDto staffDto, @MappingTarget StaffUserEntity staffUserEntity);
}
