package org.ecommerce.persistance.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import org.ecommerce.persistance.entity.CategoryEntity;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CategoryRepository implements PanacheRepository<CategoryEntity> {
}
