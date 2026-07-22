package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.SettingsService;
import org.ecommerce.common.dto.CountrySettingsDto;
import org.ecommerce.common.dto.SettingsDto;
import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.dto.StoreSettingsDto;

import java.util.Collections;
import java.util.List;

@GraphQLApi
public class SettingsResource
{
    @Inject
    SettingsService settingsService;

    @Query("settings")
    @Description("Get all settings including store settings and shipping methods")
    @RolesAllowed("SUPER_ADMIN")
    public SettingsDto getSettings()
    {
        return settingsService.getSettings();
    }

    @Query("storeSettings")
    @Description("Get all general store toggles")
    public List<StoreSettingsDto> getAllSettings()
    {
        return settingsService.getAllSettings();
    }

    @Query("shippingMethods")
    @Description("Get all available shipping options")
    public List<ShippingMethodDto> getShippingMethods()
    {
        return settingsService.getShippingMethods();
    }

    @Query("countrySettings")
    @Description("Get all country settings used for locale/currency formatting")
    public List<CountrySettingsDto> getCountrySettings()
    {
        return settingsService.getCountrySettings();
    }

    @Mutation("updateSetting")
    @Transactional
    @RolesAllowed("SUPER_ADMIN")
    public StoreSettingsDto updateSetting(@Name("key") String key, @Name("value") String value)
    {
        StoreSettingsDto dto = new StoreSettingsDto();
        dto.setKey(key);
        dto.setValue(value);
        List<StoreSettingsDto> updated = settingsService.saveStoreSettings(Collections.singletonList(dto));
        return updated.isEmpty() ? null : updated.get(0);
    }

    @Mutation("saveStoreSettings")
    @Transactional
    @RolesAllowed("SUPER_ADMIN")
    public List<StoreSettingsDto> saveStoreSettings(@Name("storeSettingsDto") List<StoreSettingsDto> storeSettingsDto)
    {
        return settingsService.saveStoreSettings(storeSettingsDto);
    }

    @Mutation("saveShippingMethod")
    @Transactional
    @RolesAllowed("SUPER_ADMIN")
    public ShippingMethodDto saveShippingMethod(@Name("methodDto") ShippingMethodDto methodDto)
    {
        return settingsService.saveShippingMethod(methodDto);
    }
}