package org.ecommerce.backend.api.graphql;

import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.backend.service.CategoryService;
import org.eclipse.microprofile.graphql.*;

import jakarta.inject.Inject;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GraphQLApi
public class CategoryResource
{
    @Inject
    CategoryService categoryService;

    @Query("allCategories")
    @Description("Get all categories")
    public List<CategoryDto> getAllCategories(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        return categoryService.getAllCategories(pageRequest, filterRequest);
    }

    @Query("categoryCount")
    public long categoryCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        return categoryService.categoryCount(filterRequest);
    }

    @Query("category")
    @Description("Get a category by ID")
    public Optional<CategoryDto> getCategoryById(@Name("id") UUID id)
    {
        return categoryService.getCategoryById(id);
    }

    @Mutation("createCategory")
    @Description("Create a new category")
    public void createCategory(@Name("categoryDto") CategoryDto categoryDto)
    {
        categoryService.createCategory(categoryDto);
    }

    @Mutation("updateCategory")
    @Description("Update an existing category")
    public void updateCategory(@Name("id") UUID id, @Name("categoryDto") CategoryDto categoryDto)
    {
        categoryService.updateCategory(id, categoryDto);
    }

    @Mutation("deleteCategory")
    @Description("Delete a category")
    public void deleteCategory(@Name("id") UUID id)
    {
        categoryService.deleteCategory(id);
    }
}
