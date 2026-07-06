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

/**
 * Service managing multipart file storage uploads, temporary archive caching, secure decompression,
 * directory-stripping logic, and line-ending normalizations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZipExtractionService {

    private final StorageConfig storageConfig;

    /**
     * Stashes a multipart form upload file onto a temporary location, initializes a tracking UUID,
     * extracts archive data sets safely to prevent path traversal vectors, and automatically
     * recycles local file dumps.
     *
     * @param file the raw multipart archive bundle resource provided by HTTP client requests
     * @return unique tracking string token assigned to the resulting output execution workspace
     * @throws IOException if directory access permissions fail or unpacking exceptions interrupt
     *                     operations
     */
    public String upload(MultipartFile file) throws IOException {
        Files.createDirectories(storageConfig.getUploadDir());

        Path tempZip = Files.createTempFile("upload-", ".zip");
        file.transferTo(tempZip);

        String uuid = UUID.randomUUID().toString();
        Path extractDir = storageConfig.getUploadDir().resolve(uuid);

        log.info(
            "Successfully received uploaded file [{}]. Generated processing session UUID: [{}], unpacking target path: [{}]",
            file.getOriginalFilename(), uuid, extractDir.toAbsolutePath());

        Files.createDirectories(extractDir);

        try {
            extractZipSafely(tempZip, extractDir);
            log.info(
                "Extraction lifecycle successfully finished for session task tracking reference token: [{}]",
                uuid);
        } catch (IOException e) {
            log.error(
                "Fatal exception or structural parsing collapse encountered while extracting zip bundle context for UUID: [{}]",
                uuid, e);
            throw e;
        } finally {
            // Guarantee that intermediate temporary cached storage uploads get destroyed safely
            boolean deleted = Files.deleteIfExists(tempZip);
            if (log.isDebugEnabled()) {
                log.debug(
                    "Temporary file storage clean up execution step for path: {}. File deleted: {}",
                    tempZip.toAbsolutePath(), deleted);
            }
        }

        return uuid;
    }

    /**
     * Iterates through an unpacked archive payload sequence, validating that file components don't
     * violate underlying location boundaries, and flattens out nested baseline paths.
     *
     * @param zipFile   local absolute system path referencing the source zip file resource
     * @param targetDir target baseline folder mapping where contents should be placed
     * @throws IOException if a malformed payload breaks boundaries or filesystem writes fail
     */
    private void extractZipSafely(Path zipFile, Path targetDir) throws IOException {
        try (
            InputStream fis = Files.newInputStream(zipFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)
        ) {

            ZipArchiveEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Flatten out the folder hierarchy layout inside the archive to match structure scopes
                String strippedName = stripFirstDirectory(entryName);
                if (strippedName.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug(
                            "Skipping root folder entry footprint segment during extraction path evaluation: {}",
                            entryName);
                    }
                    continue;
                }

                Path resolvedPath = targetDir.resolve(strippedName).normalize();

                // Prevent Zip Slip vulnerability path traversal attacks
                if (!resolvedPath.startsWith(targetDir)) {
                    log.error(
                        "Security boundary violation detected! Malicious path manipulation found in file entry: {}",
                        entry.getName());
                    throw new IOException("Bad zip entry path trajectory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);

                    // Re-align text documents to uniform line ending formats
                    normalizeTextFile(resolvedPath);
                }
            }
        }
    }

    /**
     * Strips away the topmost prefix directory node from a given relative path string. Prevents
     * unexpected root container nesting when users wrap exports in sub-folders. * <p>Example:
     * "root_dir/sub_folder/file.txt" becomes "sub_folder/file.txt"</p>
     *
     * @param path the original nested archive entry relative path string
     * @return stripped path string segment or an empty string if the input represents a top-level
     * directory root
     */
    private String stripFirstDirectory(String path) {
        // Enforce unified slash separators to prevent matching breaks across distinct operating systems
        path = path.replace("\\", "/");
        int firstSlash = path.indexOf('/');
        if (firstSlash != -1) {
            return path.substring(firstSlash + 1);
        }
        return path;
    }

    /**
     * Normalizes line break feeds within plain-text elements down to canonical platform formats.
     * Gracefully ignores errors when processing image/audio binaries.
     *
     * @param file target path referencing file item to parse and normalize
     */
    private void normalizeTextFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // Translate windows-based CRLF break lines to UNIX style layout standards
            content = content.replace("\r\n", "\n");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Catch structural parsing failures quietly to skip adjustments over binary media streams
        }
    }
}