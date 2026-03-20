package org.ecommerce.backend.api.graphql;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.ecommerce.backend.mapper.BrandMapper;
import org.ecommerce.backend.service.BrandService;
import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;

import java.util.List;
import java.util.UUID;

@GraphQLApi
public class BrandResource
{
    @Inject
    BrandService brandService;

    @Inject
    BrandMapper brandMapper;

    @Query("allBrands")
    public List<BrandDto> getAllBrands(@Name("pageRequest") PageRequest pageRequest, @Name("filterRequest") FilterRequest filterRequest)
    {
        return brandMapper.mapEntityToDtoList(brandService.getAllBrands(pageRequest, filterRequest));
    }

    @Query("brandCount")
    public long brandCount(@Name("filterRequest") FilterRequest filterRequest)
    {
        return brandService.brandCount(filterRequest);
    }

    @Query("brand")
    public BrandDto getBrandById(@Name("id") UUID id)
    {
        return brandMapper.mapEntityToDto(brandService.getBrandById(id));
    }

    @Mutation("createBrand")
    public void createBrand(@Name("brandDto") BrandDto brandDto)
    {
        if (brandDto == null) {
            throw new IllegalArgumentException("BrandDto is cannot be empty");
        }
        brandService.createBrand(brandDto);
    }

    @Mutation("updateBrand")
    public void updateBrand(@Name("id") UUID id, @Name("brandDto") BrandDto brandDto)
    {
        if (brandDto == null) {
            throw new IllegalArgumentException("BrandDto is cannot be empty");
        }
        brandService.updateBrand(id, brandDto);

    }

    @Mutation("deleteBrand")
    public void deleteBrand(@Name("id") UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("Brand id is null");
        }
        brandService.deleteBrand(id);
    }
}
