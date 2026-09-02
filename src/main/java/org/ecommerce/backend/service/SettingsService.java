package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.SettingsMapper;
import org.ecommerce.common.dto.CountrySettingsDto;
import org.ecommerce.common.dto.SettingsDto;
import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.dto.StoreSettingsDto;
import org.ecommerce.common.entity.ShippingMethodEntity;
import org.ecommerce.common.entity.StoreSettingsEntity;
import org.ecommerce.common.repository.CountrySettingsRepository;
import org.ecommerce.common.repository.SettingsRepository;
import org.ecommerce.common.repository.ShippingMethodRepository;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SettingsService
{

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ShippingMethodRepository shippingMethodRepository;

    @Inject
    CountrySettingsRepository countrySettingsRepository;

    @Inject
    SettingsMapper settingsMapper;

    public SettingsDto getSettings()
    {
        SettingsDto settingsDto = new SettingsDto();
        settingsDto.setStoreSettings(getAllSettings());
        settingsDto.setShippingMethods(getShippingMethods());
        settingsDto.setCountrySettings(getCountrySettings());
        return settingsDto;
    }

    public List<StoreSettingsDto> getAllSettings()
    {
        return settingsMapper.mapStoreSettingsEntityToDtoList(settingsRepository.getAllStoreSettings());
    }

    public List<ShippingMethodDto> getShippingMethods()
    {
        return settingsMapper.mapShippingMethodEntityToDtoList(shippingMethodRepository.listAll());
    }

    public List<CountrySettingsDto> getCountrySettings()
    {
        return settingsMapper.mapCountrySettingsEntityToDtoList(countrySettingsRepository.listAll());
    }

    public List<StoreSettingsDto> saveStoreSettings(List<StoreSettingsDto> settings)
    {
        return settings.stream().map(dto -> {
            StoreSettingsEntity entity = StoreSettingsEntity.findById(dto.getKey());
            if (entity == null) {
                entity = new StoreSettingsEntity();
                entity.setKey(dto.getKey());
            }
            settingsMapper.mapStoreSettingsDtoToEntity(dto, entity);
            settingsRepository.saveStoreSettings(entity);
            return settingsMapper.mapStoreSettingsEntityToDto(entity);
        }).collect(Collectors.toList());
    }

    public ShippingMethodDto saveShippingMethod(ShippingMethodDto methodDto)
    {
        ShippingMethodEntity entity;
        if (methodDto.getId() == null) {
            entity = settingsMapper.mapShippingMethodDtoToEntity(methodDto, new ShippingMethodEntity());
        } else {
            entity = shippingMethodRepository.findById(methodDto.getId());
            if (entity == null) {
                entity = new ShippingMethodEntity();
                entity.setId(methodDto.getId());
            }
            settingsMapper.mapShippingMethodDtoToEntity(methodDto, entity);
        }
        entity = shippingMethodRepository.save(entity);
        return settingsMapper.mapShippingMethodEntityToDto(entity);
    }
}
