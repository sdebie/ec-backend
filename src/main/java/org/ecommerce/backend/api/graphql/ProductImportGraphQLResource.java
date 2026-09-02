package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;
import org.ecommerce.backend.mapper.ImportBatchDtoMapper;
import org.ecommerce.backend.mapper.ProductComparisonMapper;
import org.ecommerce.backend.mapper.ProductPriceComparisonMapper;
import org.ecommerce.common.dto.ProductComparisonDto;
import org.ecommerce.common.dto.ProductPriceComparisonDto;
import org.ecommerce.common.dto.ProductImportBatchDto;
import org.ecommerce.common.repository.ProductImportBatchRepository;
import org.ecommerce.common.repository.ProductImportStagedRepository;
import org.ecommerce.common.repository.ProductPriceImportBatchRepository;
import org.ecommerce.common.repository.ProductPriceImportStagedRepository;

import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
@GraphQLApi
public class ProductImportGraphQLResource {

    @Inject
    ProductImportBatchRepository productImportBatchRepository;

    @Inject
    ProductImportStagedRepository productImportStagedRepository;

    @Inject
    ProductPriceImportBatchRepository productPriceImportBatchRepository;

    @Inject
    ProductPriceImportStagedRepository productPriceImportStagedRepository;

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
        return productComparisonMapper.toDtos(productImportStagedRepository.findByBatchId(batchId));
    }

    @Query("productImportBatches")
    @Description("Returns the list of all product upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductImportBatches() {
        return productImportBatchRepository.listAllOrderByCreatedAtDesc()
                .stream()
                .map(importBatchDtoMapper::fromProductBatch)
                .collect(Collectors.toList());
    }

    @Query("getPriceImportRows")
    @Description("Returns the list of product price import rows for a given batch ID")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductPriceComparisonDto> getPriceImportRows(@Name("batchId") UUID batchId) {
        return productPriceComparisonMapper.toDtos(productPriceImportStagedRepository.findByBatchId(batchId));
    }

    @Query("productPriceImportBatches")
    @Description("Returns the list of all product price upload batches")
    @Transactional(value = TxType.SUPPORTS)
    @RolesAllowed({"SUPER_ADMIN", "CATALOG_MANAGER"})
    public List<ProductImportBatchDto> getProductPriceImportBatches() {
        return productPriceImportBatchRepository.listAllOrderByCreatedAtDesc()
                .stream()
                .map(importBatchDtoMapper::fromProductPriceBatch)
                .collect(Collectors.toList());
    }


}
