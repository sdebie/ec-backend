package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.ecommerce.backend.mapper.BrandMapper;
import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.exception.BrandAlreadyExistsException;
import org.ecommerce.common.exception.BrandNotFoundException;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.BrandRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class BrandService
{
    @Inject
    BrandMapper brandMapper;

    @Inject
    BrandRepository brandRepository;

    public List<BrandDto> getAllBrands(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<BrandEntity> brandEntities = brandRepository.findAll(pageRequest, filterRequest);
        return brandMapper.mapEntityToDto(brandEntities);
    }

    public long brandCount(FilterRequest filterRequest)
    {
        return brandRepository.count(filterRequest);
    }

    public BrandDto getBrandById(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("Brand id is null");
        }

        BrandEntity brandEntity = brandRepository.findById(id);
        if (brandEntity == null) {
            throw new BrandNotFoundException("Brand id " + id + " not found");
        }

        return brandMapper.mapEntityToDto(brandEntity);
    }

    @Transactional
    public void createBrand(BrandDto brandDto)
    {
        try {

            if (validateFields(brandDto)) {

                if (brandDto.getId() != null) {
                    BrandEntity brandEntity = brandRepository.findById(brandDto.getId());
                    if (brandEntity != null) {
                        throw new BrandAlreadyExistsException("Brand with id " + brandDto.getId() + " already exists");
                    }
                }

                if (brandRepository.findByNameExcludingId(brandDto.getName(), null) != null) {
                    throw new BrandAlreadyExistsException("Brand with name '" + brandDto.getName() + "' already exists");
                }

                if (brandRepository.findBySlugExcludingId(brandDto.getSlug(), null) != null) {
                    throw new BrandAlreadyExistsException("Brand with slug '" + brandDto.getSlug() + "' already exists");
                }

                BrandEntity brandEntity = brandMapper.mapDtoToEntity(brandDto, new BrandEntity());
                brandRepository.persist(brandEntity);
            }
        } catch (BrandNotFoundException | BrandAlreadyExistsException e) {
            log.warn("Brand create operation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error creating brand: {}", e.getMessage(), e);
            throw e;
        }
    }

    private boolean validateFields(BrandDto brandDto)
    {
        if (brandDto == null) {
            throw new IllegalArgumentException("BrandDto is null");
        }

        if (brandDto.getName() == null) {
            throw new IllegalArgumentException("Brand name is null");
        }

        if (brandDto.getSlug() == null) {
            throw new IllegalArgumentException("Brand slug is null");
        }

        return true;
    }

    @Transactional
    public void updateBrand(UUID id, BrandDto brandDto)
    {
        try {
            if (validateFields(brandDto)) {
                brandDto.setId(id);

                BrandEntity brandEntity = brandRepository.findById(id);
                if (brandEntity == null) {
                    throw new BrandNotFoundException("Brand with id " + brandDto.getId() + " not found");
                }

                if (brandRepository.findByNameExcludingId(brandDto.getName(), id) != null) {
                    throw new BrandAlreadyExistsException("Brand with name '" + brandDto.getName() + "' already exists");
                }

                if (brandRepository.findBySlugExcludingId(brandDto.getSlug(), id) != null) {
                    throw new BrandAlreadyExistsException("Brand with slug '" + brandDto.getSlug() + "' already exists");
                }

                brandMapper.mapDtoToEntity(brandDto, brandEntity);
                brandRepository.persist(brandEntity);
            }
        } catch (BrandNotFoundException | BrandAlreadyExistsException e) {
            log.warn("Brand update operation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error updating brand: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void deleteBrand(UUID id)
    {
        try {
            if (id == null) {
                throw new IllegalArgumentException("Brand id is null");
            }

            BrandEntity brandEntity = brandRepository.findById(id);
            if (brandEntity == null) {
                throw new BrandNotFoundException("Brand with id " + id + " not found");
            }

            brandRepository.delete(brandEntity);
        } catch (BrandNotFoundException | BrandAlreadyExistsException e) {
            log.warn("Brand operation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error deleting brand: {}", e.getMessage(), e);
            throw e;
        }
    }

}
