package org.ecommerce.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageZipUnpackerTest
{
    @TempDir
    Path tempDir;

    @Test
    void unpacksJpegPngWebpAtZipRootOrOneFolderAndSkipsTheRest() throws Exception
    {
        Path zip = tempDir.resolve("catalog.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "sku-1.jpg", "one");
            put(out, "folder/sku-2.png", "two");
            put(out, "nested/too/deep.jpg", "deep");
            put(out, "__MACOSX/sku-3.jpg", "mac");
            put(out, "../escape.jpg", "escape");
            put(out, "notes.txt", "text");
            put(out, "sku-4.webp", "four");
        }

        Path destination = tempDir.resolve("dest");
        Files.createDirectories(destination);
        ImageZipUnpacker.UnpackResult result = ImageZipUnpacker.unpack(zip, destination);

        assertEquals(List.of("sku-1.jpg", "sku-2.png", "sku-4.webp"),
                result.acceptedNames().stream().sorted().toList());
        assertEquals("one", Files.readString(destination.resolve("sku-1.jpg")));
        assertEquals("two", Files.readString(destination.resolve("sku-2.png")));
        assertEquals("four", Files.readString(destination.resolve("sku-4.webp")));
        assertFalse(Files.exists(destination.resolve("escape.jpg")));
        assertFalse(Files.exists(destination.resolve("deep.jpg")));
        assertFalse(Files.exists(destination.resolve("notes.txt")));
    }

    @Test
    void skipsAFileThatAlreadyExistsInTheDestination() throws Exception
    {
        Path destination = tempDir.resolve("dest");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("sku-1.jpg"), "already");

        Path zip = tempDir.resolve("catalog.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "sku-1.jpg", "replacement");
        }

        ImageZipUnpacker.UnpackResult result = ImageZipUnpacker.unpack(zip, destination);

        assertTrue(result.acceptedNames().isEmpty());
        assertEquals(List.of("sku-1.jpg"), result.skippedNames());
        assertEquals("already", Files.readString(destination.resolve("sku-1.jpg")));
    }

    private static void put(ZipOutputStream out, String name, String content) throws IOException
    {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
