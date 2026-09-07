package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.backend.mapper.ProductComparisonMapper;
import org.ecommerce.backend.mapper.ProductPriceComparisonMapper;
import org.ecommerce.backend.service.import_engine.ProductImportOrchestrator;
import org.ecommerce.backend.service.import_engine.ProductPriceImportOrchestrator;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
@GraphQLApi
public class ProductImportGraphQLResource {

    @Inject
    ProductImportOrchestrator productOrchestrator;

    @Inject
    ProductPriceImportOrchestrator priceOrchestrator;

    @Inject
    ProductComparisonMapper productComparisonMapper;

    @Inject
    ProductPriceComparisonMapper productPriceComparisonMapper;

    @Inject
    ImportBatchDtoMapper importBatchDtoMapper;

    @Query("importRows")
    @Description("Returns the list of product import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductComparisonDto> getImportRows(@Name("batchId") UUID batchId) {
        return productComparisonMapper.toDtos(productOrchestrator.findByBatchId(batchId));
    }

    @Query("productImportBatches")
    @Description("Returns the list of all product upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductImportBatches() {
        return productOrchestrator.listAllOrderByCreatedAtDesc()
                .stream()
                .map(batch -> {
                    ProductImportBatchDto dto = importBatchDtoMapper.fromProductBatch(batch);
                    productOrchestrator.overlayMissingProgress(batch, dto);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Query("getPriceImportRows")
    @Description("Returns the list of product price import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductPriceComparisonDto> getPriceImportRows(@Name("batchId") UUID batchId) {
        return productPriceComparisonMapper.toDtos(priceOrchestrator.findByBatchId(batchId));
    }

    @Query("productPriceImportBatches")
    @Description("Returns the list of all product price upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductPriceImportBatches() {
        return priceOrchestrator.listAllOrderByCreatedAtDesc()
                .stream()
                .map(batch -> {
                    ProductImportBatchDto dto = importBatchDtoMapper.fromProductPriceBatch(batch);
                    priceOrchestrator.overlayMissingProgress(batch, dto);
                    return dto;
                })
                .collect(Collectors.toList());
    }


}
