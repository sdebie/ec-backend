package org.ecommerce.backend.service.import_engine;

import org.ecommerce.common.entity.ProductImportBatchEntity;
import org.ecommerce.common.entity.StaffUserEntity;
import org.ecommerce.common.enums.ImportSourceTypeEn;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProductImportOrchestratorTest
{
    @Test
    void sageBatchStoresSageSourceTypeNotFile() {
        ProductImportBatchRepository repository = mock(ProductImportBatchRepository.class);
        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = repository;

        ProductImportBatchEntity batch = orchestrator.createPendingBatch("Sage Item Import", new StaffUserEntity(), ImportSourceTypeEn.SAGE);

        assertEquals(ImportSourceTypeEn.SAGE, batch.getImportSourceTypeEn());
        verify(repository).persist(batch);
    }

    @Test
    void csvBatchStoresFileSourceType() {
        ProductImportBatchRepository repository = mock(ProductImportBatchRepository.class);
        ProductImportOrchestrator orchestrator = new ProductImportOrchestrator();
        orchestrator.batchRepository = repository;

        ProductImportBatchEntity batch = orchestrator.createPendingBatch("products.csv", new StaffUserEntity(), ImportSourceTypeEn.FILE);

        assertEquals(ImportSourceTypeEn.FILE, batch.getImportSourceTypeEn());
    }
}
