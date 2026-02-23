package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "brands")
public class BrandEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(nullable = false, unique = true)
    public String slug;

    @Column(name = "logo_url")
    public String logoUrl;

    @Column(columnDefinition = "TEXT")
    public String description;
}
