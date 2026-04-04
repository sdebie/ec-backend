package org.ecommerce.backend.api.graphql;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.service.SettingsService;
import org.ecommerce.common.dto.SettingsDto;
import org.ecommerce.common.dto.ShippingMethodDto;
import org.ecommerce.common.dto.StoreSettingsDto;

import java.util.List;
import java.util.Collections;

@GraphQLApi
public class SettingsResource
{
    @Inject
    SettingsService settingsService;

    @Query("settings")
    @Description("Get all settings including store settings and shipping methods")
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

    @Mutation("updateSetting")
    @Transactional
    public StoreSettingsDto updateSetting(@Name("key") String key, @Name("value") String value)
    {
        StoreSettingsDto dto = new StoreSettingsDto();
        dto.key = key;
        dto.value = value;
        List<StoreSettingsDto> updated = settingsService.saveStoreSettings(Collections.singletonList(dto));
        return updated.isEmpty() ? null : updated.get(0);
    }

    @Mutation("saveStoreSettings")
    @Transactional
    public List<StoreSettingsDto> saveStoreSettings(@Name("storeSettingsDto") List<StoreSettingsDto> storeSettingsDto)
    {
        return settingsService.saveStoreSettings(storeSettingsDto);
    }

    @Mutation("saveShippingMethod")
    @Transactional
    public ShippingMethodDto saveShippingMethod(@Name("methodDto") ShippingMethodDto methodDto)
    {
        return settingsService.saveShippingMethod(methodDto);
    }
}