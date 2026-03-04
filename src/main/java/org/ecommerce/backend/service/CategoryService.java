package org.ecommerce.backend.service;

import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.repository.CategoryRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CategoryService
{
    @Inject
    CategoryRepository categoryRepository;

    public List<CategoryEntity> getAllCategories()
    {
        return categoryRepository.listAll();
    }

    public Optional<CategoryEntity> getCategoryById(Long id)
    {
        return Optional.ofNullable(categoryRepository.findById(id));
    }

    @Transactional
    public CategoryEntity createCategory(CategoryEntity category)
    {
        categoryRepository.persist(category);
        return category;
    }

    @Transactional
    public CategoryEntity updateCategory(CategoryEntity category)
    {
        return categoryRepository.getEntityManager().merge(category);
    }

    @Transactional
    public void deleteCategory(Long id)
    {
        categoryRepository.deleteById(id);
    }
}
