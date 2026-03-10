package org.ecommerce.backend.api.graphql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.ProductImportService;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductUploadBatchDto;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class ProductUploadGraphQLResource {

    @Inject
    ProductImportService importService;

    @Query("importRows")
    @Description("Returns the list of product import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductComparisonDto> getImportRows(@Name("batchId") UUID batchId) {
        // We sort by SKU or created_at to keep the list stable for the user
        return importService.getProductImportRows(batchId);
    }

    @Query("productUploadBatches")
    @Description("Returns the list of all product upload batches")
    @Transactional(value = TxType.SUPPORTS)
    public List<ProductUploadBatchDto> getProductUploadBatches() {
        return importService.getProductUploadBatches();
    }


}
