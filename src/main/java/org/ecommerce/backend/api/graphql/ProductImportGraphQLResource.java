package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.service.ProductImportService;
import org.ecommerce.backend.service.ProductPriceImportService;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class ProductImportGraphQLResource {

    @Inject
    ProductImportService importService;

    @Inject
    ProductPriceImportService productPriceImportService;

    @Query("importRows")
    @Description("Returns the list of product import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductComparisonDto> getImportRows(@Name("batchId") UUID batchId) {
        // We sort by SKU or created_at to keep the list stable for the user
        return importService.getProductImportRows(batchId);
    }

    @Query("productImportBatches")
    @Description("Returns the list of all product upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductImportBatches() {
        return importService.getProductImportBatches();
    }


    @Query("getPriceImportRows")
    @Description("Returns the list of product price import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductPriceComparisonDto> getPriceImportRows(@Name("batchId") UUID batchId) {
        // We sort by SKU or created_at to keep the list stable for the user
        return productPriceImportService.getProductPriceImportRows(batchId);
    }

    @Query("productPriceImportBatches")
    @Description("Returns the list of all product price upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductPriceImportBatches() {
        return productPriceImportService.getProductPriceImportBatches();
    }


}
