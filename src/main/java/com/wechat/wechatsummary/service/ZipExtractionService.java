package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZipExtractionService {

    private final StorageConfig storageConfig;

    public void upload(MultipartFile file) throws IOException {
        Files.createDirectories(storageConfig.getUploadDir());

        Path tempZip = Files.createTempFile("upload-", ".zip");

        file.transferTo(tempZip);

        Path extractDir = storageConfig.getUploadDir().resolve(
            UUID.randomUUID().toString()
        );

        Files.createDirectories(extractDir);

        extractZipSafely(tempZip, extractDir);

        Files.deleteIfExists(tempZip);

    }

    private void extractZipSafely(Path zipFile, Path targetDir)
        throws IOException {

        try (
            InputStream fis = Files.newInputStream(zipFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            ZipArchiveInputStream zis =
                new ZipArchiveInputStream(bis)
        ) {

            ZipArchiveEntry entry;

            while ((entry = zis.getNextEntry()) != null) {

                Path resolvedPath = targetDir.resolve(entry.getName())
                    .normalize();

                // Prevent Zip Slip vulnerability
                if (!resolvedPath.startsWith(targetDir)) {
                    throw new IOException(
                        "Bad zip entry: " + entry.getName()
                    );
                }

                if (entry.isDirectory()) {

                    Files.createDirectories(resolvedPath);

                } else {

                    Files.createDirectories(
                        resolvedPath.getParent()
                    );

                    Files.copy(
                        zis,
                        resolvedPath,
                        StandardCopyOption.REPLACE_EXISTING
                    );

                    // Optional: normalize line endings
                    normalizeTextFile(resolvedPath);
                }
            }
        }
    }

    private void normalizeTextFile(Path file) {

        try {

            String content = Files.readString(
                file,
                StandardCharsets.UTF_8
            );

            // Convert CRLF -> LF
            content = content.replace("\r\n", "\n");

            Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8
            );

        } catch (Exception ignored) {
            // ignore binary files
        }
    }
}
