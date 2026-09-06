package org.ecommerce.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageServiceExistingFilenamesTest
{
    @TempDir
    Path storageRoot;

    private ImageService imageService;

    @BeforeEach
    void setUp()
    {
        imageService = new ImageService();
        imageService.storagePath = storageRoot.toString();
    }

    @Test
    void returnsOnlyNamesThatExistInTheDestinationDirectory() throws Exception
    {
        Path products = storageRoot.resolve("products");
        Files.createDirectories(products);
        Files.writeString(products.resolve("sku-1.jpg"), "one");
        Files.writeString(products.resolve("sku-2.jpg"), "two");
        Files.createDirectories(storageRoot.resolve("other"));
        Files.writeString(storageRoot.resolve("other").resolve("sku-3.jpg"), "three");

        List<String> existing = imageService.findExistingFilenames(
                "products",
                List.of("sku-1.jpg", "sku-3.jpg", "missing.jpg"));

        assertEquals(List.of("sku-1.jpg"), existing);
    }

    @Test
    void emptyDirectoryChecksTheStorageRootNotSubfolders() throws Exception
    {
        Files.writeString(storageRoot.resolve("root.jpg"), "root");
        Files.createDirectories(storageRoot.resolve("products"));
        Files.writeString(storageRoot.resolve("products").resolve("nested.jpg"), "nested");

        List<String> existing = imageService.findExistingFilenames("", List.of("root.jpg", "nested.jpg"));

        assertEquals(List.of("root.jpg"), existing);
    }

    @Test
    void ignoresAThumbnailThatHasNoOriginalInTheDestination() throws Exception
    {
        Files.createDirectories(storageRoot.resolve("products"));
        Path thumbs = storageRoot.resolve("thumbnails").resolve("products");
        Files.createDirectories(thumbs);
        Files.writeString(thumbs.resolve("sku-1.jpg"), "thumb-only");

        List<String> existing = imageService.findExistingFilenames("products", List.of("sku-1.jpg"));

        assertTrue(existing.isEmpty());
    }

    @Test
    void usesBasenameSoARelativePathCannotEscapeTheDestination() throws Exception
    {
        Files.writeString(storageRoot.resolve("secret.jpg"), "secret");
        Files.createDirectories(storageRoot.resolve("products"));

        List<String> existing = imageService.findExistingFilenames(
                "products",
                List.of("../secret.jpg"));

        assertTrue(existing.isEmpty());
    }

    @Test
    void rejectsADestinationDirectoryThatEscapesStorage()
    {
        assertThrows(IllegalArgumentException.class,
                () -> imageService.findExistingFilenames("../outside", List.of("sku-1.jpg")));
    }

    @Test
    void rejectsMoreThanTwoThousandNames()
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            names.add("sku-" + i + ".jpg");
        }

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> imageService.findExistingFilenames("products", names));

        assertTrue(thrown.getMessage().contains("2000"));
    }

    @Test
    void emptyFilenameListReturnsEmptyWithoutCreatingDirectories()
    {
        assertEquals(List.of(), imageService.findExistingFilenames("products", List.<String>of()));
        assertTrue(Files.notExists(storageRoot.resolve("products")));
    }
}
