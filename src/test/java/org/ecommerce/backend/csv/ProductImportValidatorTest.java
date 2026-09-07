package org.ecommerce.backend.csv;

import org.ecommerce.common.entity.ProductImportStagedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImportValidatorTest
{
    @TempDir
    Path storageRoot;

    private ProductImportValidator validator;

    @BeforeEach
    void setUp()
    {
        validator = new ProductImportValidator();
        validator.storagePath = storageRoot.toString();
    }

    @Test
    void leadingSlashCsvPathFindsTheFileUnderStorage() throws Exception
    {
        Path products = storageRoot.resolve("02");
        Files.createDirectories(products);
        Files.writeString(products.resolve("K-MSK-FFP1-Dro.jpg"), "ok");

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setImages("/02/K-MSK-FFP1-Dro.jpg");
        List<String> validationErrors = new ArrayList<>();

        validator.validateImages(staged, validationErrors);

        assertTrue(validationErrors.isEmpty(), validationErrors.toString());
        assertNull(staged.getImageErrors());
    }

    @Test
    void commaInFilenameIsNotSplitIntoTwoMissingImages() throws Exception
    {
        Path products = storageRoot.resolve("02");
        Files.createDirectories(products);
        Files.writeString(products.resolve("DW-ARC9,6-SS-S-SKB.jpg"), "ok");

        ProductImportStagedEntity staged = new ProductImportStagedEntity();
        staged.setImages("/02/DW-ARC9,6-SS-S-SKB.jpg");
        List<String> validationErrors = new ArrayList<>();

        validator.validateImages(staged, validationErrors);

        assertTrue(validationErrors.isEmpty(), validationErrors.toString());
        assertNull(staged.getImageErrors());
    }
}
