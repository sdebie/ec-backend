package org.ecommerce.backend.service;

import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageServiceLandTest
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
    void landWritesOriginalsAndSkipsExistingWithoutCreatingThumbnails() throws Exception
    {
        Path products = storageRoot.resolve("products");
        Files.createDirectories(products);
        Files.writeString(products.resolve("sku-1.jpg"), "already");

        Path incomingExisting = Files.writeString(storageRoot.resolve("incoming-1.jpg"), "new-bytes");
        Path incomingMissing = Files.writeString(storageRoot.resolve("incoming-2.jpg"), "fresh");

        ImageService.BulkLandResult result = imageService.landBulkImages(
                List.of(upload("sku-1.jpg", incomingExisting), upload("sku-2.jpg", incomingMissing)),
                "products");

        assertEquals(List.of("sku-2.jpg"), result.acceptedNames());
        assertEquals(List.of("sku-1.jpg"), result.skippedNames());
        assertEquals("fresh", Files.readString(products.resolve("sku-2.jpg")));
        assertEquals("already", Files.readString(products.resolve("sku-1.jpg")));
        assertFalse(Files.exists(storageRoot.resolve("thumbnails").resolve("products").resolve("sku-2.jpg")));
    }

    @Test
    void processLandedImageWritesAThumbnail() throws Exception
    {
        Path products = storageRoot.resolve("products");
        Files.createDirectories(products);
        Files.writeString(products.resolve("sku-2.jpg"), "fresh");

        imageService.processLandedBulkImage("products/sku-2.jpg");

        assertTrue(Files.isRegularFile(storageRoot.resolve("thumbnails").resolve("products").resolve("sku-2.jpg")));
    }

    private static FileUpload upload(String name, Path path)
    {
        FileUpload file = mock(FileUpload.class);
        when(file.fileName()).thenReturn(name);
        when(file.filePath()).thenReturn(path);
        return file;
    }
}
