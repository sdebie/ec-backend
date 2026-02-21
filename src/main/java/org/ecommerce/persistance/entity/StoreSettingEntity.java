package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "store_settings")
public class StoreSettingEntity extends PanacheEntityBase {
    @Id
    @Column(name = "setting_key")
    public String key;

    @Column(name = "setting_value", nullable = false)
    public String value;

    public String description;
}