package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.CategoryService;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.dto.PageResponse;
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
    public List<CategoryDto> getAllCategories(
            @Name("pageRequest") PageRequest pageRequest,
            @Name("filterRequest") FilterRequest filterRequest,
            @Name("includeSubCategories") @DefaultValue("false") boolean includeSubCategories)
    {
        return categoryService.getAllCategories(pageRequest, filterRequest, includeSubCategories);
    }

    @Query("getCategories")
    @Description("Get paginated categories for storefront filtering. Returns all categories including subcategories.")
    public PageResponse<CategoryDto> getCategories(@Name("pageIndex") @DefaultValue("0") int pageIndex, @Name("pageSize") @DefaultValue("500") int pageSize, @Name("filterRequest") FilterRequest filterRequest)
    {
        int effectivePageSize = Math.min(pageSize, 500);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageIndex(pageIndex);
        pageRequest.setPageSize(effectivePageSize);

        List<CategoryDto> content = categoryService.getAllCategories(pageRequest, filterRequest, true);
        long totalElements = categoryService.categoryCount(filterRequest);
        int totalPages = (int) Math.ceil((double) totalElements / effectivePageSize);

        return new PageResponse<>(content, totalElements, totalPages, pageIndex, effectivePageSize);
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
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public void createCategory(@Name("categoryDto") CategoryDto categoryDto)
    {
        categoryService.createCategory(categoryDto);
    }

    @Mutation("updateCategory")
    @Description("Update an existing category")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public void updateCategory(@Name("id") UUID id, @Name("categoryDto") CategoryDto categoryDto)
    {
        categoryService.updateCategory(id, categoryDto);
    }

    @Mutation("deleteCategory")
    @Description("Delete a category")
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public void deleteCategory(@Name("id") UUID id)
    {
        categoryService.deleteCategory(id);
    }
}
