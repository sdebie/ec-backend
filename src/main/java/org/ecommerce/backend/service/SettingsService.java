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
import org.ecommerce.common.repository.StoreSettingsRepository;
import org.ecommerce.common.repository.ShippingMethodRepository;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SettingsService
{

    @Inject
    StoreSettingsRepository storeSettingsRepository;

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
        return settingsMapper.mapStoreSettingsEntityToDtoList(storeSettingsRepository.listAll());
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
            StoreSettingsEntity entity = storeSettingsRepository.findById(dto.getKey());
            boolean isNew = entity == null;
            if (isNew) {
                entity = new StoreSettingsEntity();
                entity.setKey(dto.getKey());
            }
            settingsMapper.mapStoreSettingsDtoToEntity(dto, entity);
            if (isNew) {
                // An already-managed row (found above) is updated by dirty checking on commit;
                // only a genuinely new row needs an explicit insert.
                storeSettingsRepository.persist(entity);
            }
            return settingsMapper.mapStoreSettingsEntityToDto(entity);
        }).collect(Collectors.toList());
    }

    public ShippingMethodDto saveShippingMethod(ShippingMethodDto methodDto)
    {
        ShippingMethodEntity entity = methodDto.getId() == null ? null : shippingMethodRepository.findById(methodDto.getId());
        boolean isNew = entity == null;
        if (isNew) {
            entity = new ShippingMethodEntity();
            if (methodDto.getId() != null) {
                entity.setId(methodDto.getId());
            }
        }
        settingsMapper.mapShippingMethodDtoToEntity(methodDto, entity);
        if (isNew) {
            // An already-managed row (found above) is updated by dirty checking on commit;
            // only a genuinely new row needs an explicit insert.
            shippingMethodRepository.persist(entity);
        }
        return settingsMapper.mapShippingMethodEntityToDto(entity);
    }
}
