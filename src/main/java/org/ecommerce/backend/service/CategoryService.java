package org.ecommerce.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.CategoryMapper;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.entity.CategoryEntity;
import org.ecommerce.common.exception.CategoryAlreadyExistsException;
import org.ecommerce.common.exception.CategoryNotFoundException;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.CategoryRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class CategoryService
{
    @Inject
    CategoryRepository categoryRepository;

    @Inject
    CategoryMapper categoryMapper;

    public List<CategoryDto> getAllCategories(PageRequest pageRequest, FilterRequest filterRequest)
    {
        return getAllCategories(pageRequest, filterRequest, false);
    }

    public List<CategoryDto> getAllCategories(PageRequest pageRequest, FilterRequest filterRequest, boolean includeSubCategories)
    {
        FilterRequest resolvedFilterRequest = new FilterRequest();
        if (filterRequest != null) {
            resolvedFilterRequest.setSort(filterRequest.getSort());
            resolvedFilterRequest.setFilterGroups(filterRequest.getFilterGroups());
            resolvedFilterRequest.setFilters(filterRequest.getFilters() != null
                    ? new ArrayList<>(filterRequest.getFilters())
                    : new ArrayList<>());
        } else {
            resolvedFilterRequest.setFilters(new ArrayList<>());
        }

        if (!includeSubCategories) {
            boolean alreadyFilteredToRoots = resolvedFilterRequest.getFilters().stream()
                    .anyMatch(filter -> "parent.id".equals(filter.getKey()) && filter.getOperator() == FilterOperator.IS_NULL);

            if (!alreadyFilteredToRoots) {
                resolvedFilterRequest.getFilters().add(new Filter("parent.id", FilterOperator.IS_NULL, (String) null));
            }
        }

        List<CategoryEntity> categoryEntities = categoryRepository.findAll(pageRequest, resolvedFilterRequest);
        return categoryMapper.mapEntityToDto(categoryEntities);
    }

    public long categoryCount(FilterRequest filterRequest)
    {
        return categoryRepository.count(filterRequest);
    }

    public Optional<CategoryDto> getCategoryById(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("Category id is null");
        }

        CategoryEntity categoryEntity = categoryRepository.findById(id);
        return Optional.ofNullable(categoryMapper.mapEntityToDto(categoryEntity));
    }

    @Transactional
    public void createCategory(CategoryDto categoryDto)
    {
        try {
            if (validateFields(categoryDto)) {
                if (categoryDto.getId() != null) {
                    CategoryEntity categoryEntity = categoryRepository.findById(categoryDto.getId());
                    if (categoryEntity != null) {
                        throw new CategoryAlreadyExistsException("Category with id " + categoryDto.getId() + " already exists");
                    }
                }

                if (categoryRepository.findByNameExcludingId(categoryDto.getName(), null) != null) {
                    throw new CategoryAlreadyExistsException("Category with name '" + categoryDto.getName() + "' already exists");
                }

                if (categoryRepository.findBySlugExcludingId(categoryDto.getSlug(), null) != null) {
                    throw new CategoryAlreadyExistsException("Category with slug '" + categoryDto.getSlug() + "' already exists");
                }
                CategoryEntity categoryEntity = categoryMapper.mapDtoToEntity(categoryDto, new CategoryEntity());
                categoryRepository.persist(categoryEntity);
            }
        } catch (Exception e) {
            log.error("Error creating category: {}", e.getMessage(), e);
            throw e;
        }

    }

    private boolean validateFields(CategoryDto categoryDto)
    {
        if (categoryDto.getName() == null) {
            throw new IllegalArgumentException("Category name is required");
        }

        if (categoryDto.getSlug() == null) {
            throw new IllegalArgumentException("Category slug is required");
        }

        return true;
    }

    @Transactional
    public void updateCategory(UUID id, CategoryDto categoryDto)
    {
        try {
            if (validateFields(categoryDto)) {
                categoryDto.setId(id);

                CategoryEntity categoryEntity = CategoryEntity.findById(id);
                if (categoryEntity == null) {
                    throw new CategoryNotFoundException("Category with id " + categoryDto.getId() + " not found");
                }

                if (categoryRepository.findByNameExcludingId(categoryDto.getName(), id) != null) {
                    throw new CategoryAlreadyExistsException("Category with name '" + categoryDto.getName() + "' already exists");
                }

                if (categoryRepository.findBySlugExcludingId(categoryDto.getSlug(), id) != null) {
                    throw new CategoryAlreadyExistsException("Category with slug '" + categoryDto.getSlug() + "' already exists");
                }

                categoryMapper.mapDtoToEntity(categoryDto, categoryEntity);
                categoryRepository.persist(categoryEntity);
            }
        } catch (Exception e) {
            log.error("Error updating category: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void deleteCategory(UUID id)
    {
        try {
            if (id == null) {
                throw new IllegalArgumentException("Category id is null");
            }

            CategoryEntity categoryEntity = CategoryEntity.findById(id);
            if (categoryEntity == null) {
                throw new CategoryNotFoundException("Category with id " + id + " not found");
            }

            categoryRepository.delete(categoryEntity);
        } catch (Exception e) {
            log.error("Error deleting category: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Fix category names by replacing HTML entity &amp; with ampersand &
     * This method updates all categories that contain '&amp;' in their names.
     *
     * @return the number of categories updated
     */
    @Transactional
    public long fixCategoryNamesAmpersand()
    {
        try {
            List<CategoryEntity> categoriesToFix = categoryRepository.list("name like ?1", "%&amp;%");
            long count = 0;

            for (CategoryEntity category : categoriesToFix) {
                String originalName = category.name;
                category.name = category.name.replace("&amp;", "&");
                categoryRepository.persist(category);
                count++;
                log.info("Fixed category name: '{}' -> '{}'", originalName, category.name);
            }

            log.info("Total categories fixed: {}", count);
            return count;
        } catch (Exception e) {
            log.error("Error fixing category names: {}", e.getMessage(), e);
            throw e;
        }
    }
}
