package org.ecommerce.backend.api.rest;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.StaffService;
import org.ecommerce.backend.service.import_engine.GenericImportAsyncService;
import org.ecommerce.backend.service.import_engine.ProductImportOrchestrator;
import org.ecommerce.common.dto.ImportBatchProcessStatusDto;
import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.ImportSourceTypeEn;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GenericImportResourceSageSourceTest
{
    @Test
    void sageItemImportCreatesASageSourcedProductBatch() {

        JsonWebToken jwt = mock(JsonWebToken.class);
        StaffService staffService = mock(StaffService.class);
        ProductImportOrchestrator productOrchestrator = mock(ProductImportOrchestrator.class);
        GenericImportAsyncService asyncService = mock(GenericImportAsyncService.class);

        StaffUserEntity admin = new StaffUserEntity();
        ProductImportBatchEntity batch = new ProductImportBatchEntity();
        batch.setId(UUID.randomUUID());

        when(jwt.getName()).thenReturn("admin@test.com");
        when(staffService.findByEmail("admin@test.com")).thenReturn(admin);
        when(productOrchestrator.createPendingBatch(eq("Sage Item Import"), eq(admin), eq(ImportSourceTypeEn.SAGE))).thenReturn(batch);
        when(productOrchestrator.getStatus(batch.getId())).thenReturn(new ImportBatchProcessStatusDto());

        GenericImportResource resource = new GenericImportResource();
        resource.jwt = jwt;
        resource.staffService = staffService;
        resource.productOrchestrator = productOrchestrator;
        resource.asyncService = asyncService;

        resource.triggerSageItemImport();

        verify(productOrchestrator).createPendingBatch("Sage Item Import", admin, ImportSourceTypeEn.SAGE);
    }
}
