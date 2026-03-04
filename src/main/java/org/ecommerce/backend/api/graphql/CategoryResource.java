package org.ecommerce.backend.api.graphql;

import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.backend.service.CategoryService;
import org.eclipse.microprofile.graphql.*;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@GraphQLApi
public class CategoryResource
{
    @Inject
    CategoryService categoryService;

    @Query("allCategories")
    @Description("Get all categories")
    public List<CategoryEntity> getAllCategories()
    {
        return categoryService.getAllCategories();
    }

    @Query("category")
    @Description("Get a category by ID")
    public Optional<CategoryEntity> getCategoryById(@Name("id") Long id)
    {
        return categoryService.getCategoryById(id);
    }

    @Mutation
    @Description("Create a new category")
    public CategoryEntity createCategory(CategoryEntity category)
    {
        return categoryService.createCategory(category);
    }

    @Mutation
    @Description("Update an existing category")
    public CategoryEntity updateCategory(CategoryEntity category)
    {
        return categoryService.updateCategory(category);
    }

    @Mutation
    @Description("Delete a category")
    public void deleteCategory(@Name("id") Long id)
    {
        categoryService.deleteCategory(id);
    }
}
