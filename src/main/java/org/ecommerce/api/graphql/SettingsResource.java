package org.ecommerce.api.graphql;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.persistance.entity.ShippingMethodEntity;
import org.ecommerce.persistance.entity.StoreSettingEntity;

import java.util.List;

@GraphQLApi
public class SettingsResource {

    @Inject
    EntityManager entityManager;

    @Query("allSettings")
    @Description("Get all general store toggles")
    public List<StoreSettingEntity> getAllSettings() {
        return StoreSettingEntity.listAll();
    }

    @Query("shippingMethods")
    @Description("Get all available shipping options")
    public List<ShippingMethodEntity> getShippingMethods() {
        return ShippingMethodEntity.listAll();
    }

    @Mutation("updateSetting")
    @Transactional
    public StoreSettingEntity updateSetting(String key, String value) {
        StoreSettingEntity entity = StoreSettingEntity.findById(key);
        if (entity != null) {
            entity.value = value;
        }
        return entity;
    }

    @Mutation("saveShippingMethod")
    @Transactional
    public ShippingMethodEntity saveShippingMethod(ShippingMethodEntity method) {
        if (method.id == null) {
            method.persist();
        } else {
            return entityManager.merge(method);
        }
        return method;
    }
}