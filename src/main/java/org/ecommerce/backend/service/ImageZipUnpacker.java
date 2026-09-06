package org.ecommerce.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks jpeg/png/webp from a zip root or one top-level folder into a
 * destination directory. Nested junk, {@code __MACOSX}, and {@code ..} are skipped.
 */
public final class ImageZipUnpacker
{
    static final long MAX_UNCOMPRESSED_BYTES = 2_000_000_000L;
    static final long MAX_ENTRY_BYTES = 50_000_000L;
    static final int MAX_ENTRIES = 20_000;

    public record UnpackResult(List<String> acceptedNames, List<String> skippedNames)
    {
    }

    private ImageZipUnpacker()
    {
    }

    public static UnpackResult unpack(Path zipFile, Path destinationRoot) throws IOException
    {
        Files.createDirectories(destinationRoot);
        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long uncompressedTotal = 0;
        int entriesSeen = 0;

        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entriesSeen++;
                if (entriesSeen > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Zip has too many entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }

                String fileName = zipImageFileName(entry.getName());
                if (fileName == null) {
                    drain(zip);
                    continue;
                }

                Path target = destinationRoot.resolve(fileName).normalize();
                if (!target.startsWith(destinationRoot)) {
                    drain(zip);
                    continue;
                }

                if (Files.exists(target)) {
                    skipped.add(fileName);
                    drain(zip);
                    continue;
                }

                byte[] bytes = zip.readAllBytes();
                if (bytes.length > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("Zip entry exceeds the maximum size");
                }
                uncompressedTotal += bytes.length;
                if (uncompressedTotal > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Zip uncompressed size exceeds the maximum");
                }

                Files.write(target, bytes);
                accepted.add(fileName);
            }
        }

        return new UnpackResult(List.copyOf(accepted), List.copyOf(skipped));
    }

    static String zipImageFileName(String entryPath)
    {
        if (entryPath == null || entryPath.isBlank()) {
            return null;
        }
        String normalized = entryPath.replace('\\', '/').replaceFirst("^/+", "");
        if (normalized.isBlank() || normalized.endsWith("/")) {
            return null;
        }

        String[] parts = normalized.split("/");
        List<String> filtered = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                filtered.add(part);
            }
        }
        if (filtered.isEmpty() || filtered.contains("..") || "__MACOSX".equals(filtered.getFirst())) {
            return null;
        }

        String fileName;
        if (filtered.size() == 1) {
            fileName = filtered.getFirst();
        } else if (filtered.size() == 2) {
            fileName = filtered.get(1);
        } else {
            return null;
        }

        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return fileName;
        }
        return null;
    }

    private static void drain(ZipInputStream zip) throws IOException
    {
        zip.readAllBytes();
    }
}
