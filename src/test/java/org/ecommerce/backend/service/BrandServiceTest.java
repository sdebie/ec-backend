package org.ecommerce.backend.service;

import io.quarkus.panache.mock.PanacheMock;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.backend.mapper.BrandMapper;
import org.ecommerce.common.dto.BrandDto;
import org.ecommerce.common.entity.BrandEntity;
import org.ecommerce.common.exception.BrandAlreadyExistsException;
import org.ecommerce.common.exception.BrandNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class BrandServiceTest
{
    @Inject
    BrandService brandService;

    @InjectMock
    BrandMapper brandMapper;

    @BeforeEach
    void setUp()
    {
        PanacheMock.mock(BrandEntity.class);
    }

    @Test
    void getAllBrands_shouldReturnAllBrands()
    {
        BrandEntity brand1 = new BrandEntity();
        BrandEntity brand2 = new BrandEntity();

        when(BrandEntity.listAll()).thenReturn(List.of(brand1, brand2));

        List<BrandEntity> result = brandService.getAllBrands();
        assertEquals(2, result.size());
        assertSame(brand1, result.get(0));
        assertSame(brand2, result.get(1));
    }

    @Test
    void getBrandById_shouldThrowExceptionWhenIdIsNull()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> brandService.getBrandById(null));
        assertEquals("Brand id is null", ex.getMessage());
    }

    @Test
    void getBrandById_shouldReturnBrandWhenFound()
    {
        UUID id = UUID.randomUUID();
        BrandEntity brandEntity = new BrandEntity();

        when(BrandEntity.findById(id)).thenReturn(brandEntity);

        BrandEntity result = brandService.getBrandById(id);
        assertSame(brandEntity, result);
    }

    @Test
    void getBrandById_shouldThrowBrandNotFoundWhenMissing()
    {
        UUID id = UUID.randomUUID();

        when(BrandEntity.findById(id)).thenReturn(null);

        BrandNotFoundException ex = assertThrows(BrandNotFoundException.class, () -> brandService.getBrandById(id));
        assertEquals("Brand id " + id + " not found", ex.getMessage());
    }

    @Test
    void createBrand_shouldThrowExceptionWhenDtoIsNull()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> brandService.createBrand(null));
        assertEquals("BrandDto is null", ex.getMessage());
    }

    @Test
    void createBrand_shouldThrowExceptionWhenRequiredFieldIsNull()
    {
        BrandDto brandDto = new BrandDto();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> brandService.createBrand(brandDto));
        assertEquals("Brand name is null", ex.getMessage());

        brandDto.setName("Test Brand");
        ex = assertThrows(IllegalArgumentException.class, () -> brandService.createBrand(brandDto));
        assertEquals("Brand slug is null", ex.getMessage());
    }

    @Test
    void createBrand_shouldCreateBrandWhenValid()
    {
        BrandDto brandDto = new BrandDto();
        brandDto.setName("Test Brand");
        brandDto.setSlug("test-brand");
        brandDto.setDescription("Test Description");

        BrandEntity brandEntity = mock();

        when(brandMapper.mapDtoToEntity(eq(brandDto), any(BrandEntity.class))).thenReturn(brandEntity);

        brandService.createBrand(brandDto);

        verify(brandMapper).mapDtoToEntity(eq(brandDto), any(BrandEntity.class));
        verify(brandEntity).persist();
    }

    @Test
    void createBrand_shouldThrowExceptionWhenIdAlreadyExists()
    {
        UUID id = UUID.randomUUID();
        BrandDto brandDto = new BrandDto();
        brandDto.setId(id);
        brandDto.setName("Test Brand");
        brandDto.setDescription("Test Description");
        brandDto.setSlug("test-brand");

        BrandEntity existingBrandEntity = new BrandEntity();

        when(BrandEntity.findById(id)).thenReturn(existingBrandEntity);

        BrandAlreadyExistsException ex = assertThrows(BrandAlreadyExistsException.class, () -> brandService.createBrand(brandDto));
        assertEquals("Brand with id " + brandDto.getId() + " already exists", ex.getMessage());
    }

    @Test
    void updateBrand_shouldThrowExceptionWhenIdsDoNotMatch()
    {
        UUID id = UUID.randomUUID();
        UUID brandDtoId = UUID.randomUUID();

        BrandDto brandDto = new BrandDto();
        brandDto.setId(brandDtoId);
        brandDto.setName("Test Brand");
        brandDto.setDescription("Test Description");
        brandDto.setSlug("test-brand");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> brandService.updateBrand(id, brandDto));
        assertEquals("Id " + id + " does not match brand dto id " + id, ex.getMessage());
    }

    @Test
    void updateBrand_shouldThrowExceptionWhenBrandNotFound()
    {
        UUID id = UUID.randomUUID();

        BrandDto brandDto = new BrandDto();
        brandDto.setId(id);
        brandDto.setName("Test Brand");
        brandDto.setDescription("Test Description");
        brandDto.setSlug("test-brand");

        when(BrandEntity.findById(id)).thenReturn(null);

        BrandNotFoundException ex = assertThrows(BrandNotFoundException.class, () -> brandService.updateBrand(id, brandDto));
        assertEquals("Brand with id " + brandDto.getId() + " not found", ex.getMessage());
    }

    @Test
    void updateBrand_shouldUpdateBrandWhenValid()
    {
        UUID id = UUID.randomUUID();

        BrandDto brandDto = new BrandDto();
        brandDto.setId(id);
        brandDto.setName("Test Brand");
        brandDto.setDescription("Test Description");
        brandDto.setSlug("test-brand");

        BrandEntity existingBrandEntity = mock();

        when(BrandEntity.findById(id)).thenReturn(existingBrandEntity);
        when(brandMapper.mapDtoToEntity(brandDto, existingBrandEntity)).thenReturn(existingBrandEntity);

        brandService.updateBrand(id, brandDto);

        verify(brandMapper).mapDtoToEntity(brandDto, existingBrandEntity);
        verify(existingBrandEntity).persist();
    }

    @Test
    void deleteBrand_shouldThrowExceptionWhenIdIsNull()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> brandService.deleteBrand(null));
        assertEquals("Brand id is null", ex.getMessage());
    }

    @Test
    void deleteBrand_shouldThrowExceptionWhenBrandNotFound()
    {
        UUID id = UUID.randomUUID();

        when(BrandEntity.findById(id)).thenReturn(null);

        BrandNotFoundException ex = assertThrows(BrandNotFoundException.class, () -> brandService.deleteBrand(id));
        assertEquals("Brand with id " + id + " not found", ex.getMessage());
    }

    @Test
    void deleteBrand_shouldDeleteWhenBrandExists()
    {
        UUID id = UUID.randomUUID();
        BrandEntity brandEntity = mock();

        when(BrandEntity.findById(id)).thenReturn(brandEntity);
        brandService.deleteBrand(id);

        verify(brandEntity).delete();
    }
}