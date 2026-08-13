package org.ecommerce.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageServiceFileSystemTest
{
    @TempDir
    Path tempDir;

    @Test
    void normalizeDestinationDirectory_shouldNormalizeNestedRelativePaths()
    {
        ImageService imageService = new ImageService();

        assertEquals("", imageService.normalizeDestinationDirectory(null));
        assertEquals("", imageService.normalizeDestinationDirectory("   "));
        assertEquals("01", imageService.normalizeDestinationDirectory("/01/"));
        assertEquals("01/nested", imageService.normalizeDestinationDirectory("01\\nested/"));
    }

    @Test
    void normalizeDestinationDirectory_shouldRejectTraversalOutsideStorageRoot()
    {
        ImageService imageService = new ImageService();

        assertThrows(IllegalArgumentException.class, () -> imageService.normalizeDestinationDirectory("../secret"));
        assertThrows(IllegalArgumentException.class, () -> imageService.normalizeDestinationDirectory("01/../../secret"));
    }

    @Test
    void listDestinationDirectories_andImages_shouldListImagesFromThumbnailsOnly() throws IOException
    {
        ImageService imageService = new ImageService();
        imageService.storagePath = tempDir.toString();

        Files.createDirectories(tempDir.resolve("01/nested"));
        Files.createDirectories(tempDir.resolve("archived"));
        Files.createDirectories(tempDir.resolve("thumbnails/01"));
        Files.createDirectories(tempDir.resolve("thumbnails/archived"));

        Files.writeString(tempDir.resolve("01/SKU-001.jpg"), "image");
        Files.writeString(tempDir.resolve("archived/SKU-002.png"), "image");
        Files.writeString(tempDir.resolve("archived/SKU-003.webp"), "image");
        Files.writeString(tempDir.resolve("notes.txt"), "ignore");
        Files.writeString(tempDir.resolve("thumbnails/01/SKU-001.jpg"), "thumb");
        Files.writeString(tempDir.resolve("thumbnails/archived/SKU-002.png"), "thumb");

        assertEquals(
                List.of("01", "01/nested", "archived"),
                imageService.listDestinationDirectories()
        );

        assertEquals(
                List.of("01/SKU-001.jpg", "archived/SKU-002.png"),
                imageService.listImages()
        );

        ImageService.PaginatedImagesResponse page0 = imageService.listImagesPaginated(0, 1, "");
        assertEquals(2, page0.totalCount());
        assertEquals(List.of("01/SKU-001.jpg"), page0.images());

        // Each page is a discrete slice, not a cumulative 0..page range — page 1 must not
        // repeat page 0's items (a prior bug: subList(0, toIndex) always started at 0).
        ImageService.PaginatedImagesResponse page1 = imageService.listImagesPaginated(1, 1, "");
        assertEquals(2, page1.totalCount());
        assertEquals(List.of("archived/SKU-002.png"), page1.images());

        ImageService.PaginatedImagesResponse searchResult = imageService.listImagesPaginated(0, 30, "archived");
        assertEquals(1, searchResult.totalCount());
        assertEquals(List.of("archived/SKU-002.png"), searchResult.images());

        ImageService.PaginatedImagesResponse directoryResult = imageService.listImagesPaginated(0, 30, "", "archived");
        assertEquals(1, directoryResult.totalCount());
        assertEquals(List.of("archived/SKU-002.png"), directoryResult.images());

        // directory is a leading-path-segment match, not a substring match — a file whose name
        // merely contains the directory's text must not match.
        ImageService.PaginatedImagesResponse directoryPrefixOnly = imageService.listImagesPaginated(0, 30, "", "01");
        assertEquals(1, directoryPrefixOnly.totalCount());
        assertEquals(List.of("01/SKU-001.jpg"), directoryPrefixOnly.images());

        // directory and search are ANDed together.
        ImageService.PaginatedImagesResponse combined = imageService.listImagesPaginated(0, 30, "SKU-003", "archived");
        assertEquals(1, combined.totalCount());
        assertEquals(List.of("archived/SKU-003.webp"), combined.images());

        ImageService.PaginatedImagesResponse noMatch = imageService.listImagesPaginated(0, 30, "SKU-001", "archived");
        assertEquals(0, noMatch.totalCount());
    }

    @Test
    void findBulkImagePathsForSku_shouldFindRootAndNestedImagesButNeverThumbnails() throws IOException
    {
        ImageService imageService = new ImageService();
        imageService.storagePath = tempDir.toString();

        Files.createDirectories(tempDir.resolve("supplier"));
        Files.createDirectories(tempDir.resolve("thumbnails/supplier"));
        Files.writeString(tempDir.resolve("SKU-001.jpg"), "image");
        Files.writeString(tempDir.resolve("supplier/SKU-001.png"), "image");
        Files.writeString(tempDir.resolve("thumbnails/supplier/SKU-001.jpg"), "thumbnail");
        Files.writeString(tempDir.resolve("supplier/SKU-001-extra.jpg"), "image");

        assertEquals(
                List.of("SKU-001.jpg", "supplier/SKU-001.png"),
                imageService.findBulkImagePathsForSku("SKU-001")
        );
    }
}
