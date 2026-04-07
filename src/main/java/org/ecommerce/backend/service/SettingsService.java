package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.SettingsMapper;
import org.ecommerce.common.dto.SettingsDto;
import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.dto.StoreSettingsDto;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.SettingsRepository;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SettingsService {

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    SettingsMapper settingsMapper;

    public SettingsDto getSettings() {
        SettingsDto settingsDto = new SettingsDto();
        settingsDto.storeSettings = getAllSettings();
        settingsDto.shippingMethods = getShippingMethods();
        return settingsDto;
    }

    public List<StoreSettingsDto> getAllSettings() {
        return settingsMapper.mapStoreSettingsEntityToDtoList(settingsRepository.getAllStoreSettings());
    }

    public List<ShippingMethodDto> getShippingMethods() {
        return settingsMapper.mapShippingMethodEntityToDtoList(settingsRepository.getAllShippingMethods());
    }

    public List<StoreSettingsDto> saveStoreSettings(List<StoreSettingsDto> settings) {
        return settings.stream().map(dto -> {
            StoreSettingsEntity entity = StoreSettingsEntity.findById(dto.key);
            if (entity != null) {
                settingsMapper.mapStoreSettingsDtoToEntity(dto, entity);
                settingsRepository.saveStoreSettings(entity);
            }
            return settingsMapper.mapStoreSettingsEntityToDto(entity);
        }).collect(Collectors.toList());
    }
    public ShippingMethodDto saveShippingMethod(ShippingMethodDto methodDto) {
        ShippingMethodEntity entity;
        if (methodDto.id == null) {
            entity = settingsMapper.mapShippingMethodDtoToEntity(methodDto, new ShippingMethodEntity());
        } else {
            entity = ShippingMethodEntity.findById(methodDto.id);
            if (entity == null) {
                entity = new ShippingMethodEntity();
                entity.id = methodDto.id;
            }
            settingsMapper.mapShippingMethodDtoToEntity(methodDto, entity);
        }
        entity = settingsRepository.saveShippingMethod(entity);
        return settingsMapper.mapShippingMethodEntityToDto(entity);
    }
}
