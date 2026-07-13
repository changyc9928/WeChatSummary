package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.SessionResponseDTO;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private static final DateTimeFormatter CHAT_DATE_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter UPLOAD_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy/MM/dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private final StorageConfig storageConfig;
    private final ObjectMapper objectMapper;

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

    /**
     * Scans the base upload directory for active session subdirectories. Returns sorted
     * SessionResponseDTO objects with separated upload timestamps.
     */
    public List<SessionResponseDTO> listAvailableSessions() throws IOException {
        Path uploadDir = storageConfig.getUploadDir();
        if (!Files.exists(uploadDir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(uploadDir)) {
            return stream
                .filter(Files::isDirectory)
                .filter(dir -> {
                    String dirName = dir.getFileName().toString();
                    return !dirName.equalsIgnoreCase("outputs") && !dirName.startsWith(".");
                })
                .sorted((dir1, dir2) -> {
                    try {
                        Instant time1 = Files.readAttributes(dir1, BasicFileAttributes.class)
                            .creationTime().toInstant();
                        Instant time2 = Files.readAttributes(dir2, BasicFileAttributes.class)
                            .creationTime().toInstant();
                        return time2.compareTo(time1);
                    } catch (IOException e) {
                        log.warn("Sorting evaluation failed between paths [{}] and [{}]", dir1,
                            dir2);
                        return 0;
                    }
                })
                .map(dir -> {
                    String uuid = dir.getFileName().toString();
                    String chatDisplayTitle = buildChatDisplayTitle(dir);
                    String uploadTimeStr = getFolderCreationTime(dir);

                    return new SessionResponseDTO(uuid, chatDisplayTitle, uploadTimeStr);
                })
                .collect(Collectors.toList());
        }
    }

    /**
     * Extracts folder creation attribute as a standalone string.
     */
    private String getFolderCreationTime(Path directory) {
        try {
            BasicFileAttributes attr = Files.readAttributes(directory, BasicFileAttributes.class);
            return UPLOAD_TIME_FORMATTER.format(attr.creationTime().toInstant());
        } catch (IOException e) {
            log.warn("Could not discover system creation timeline data for folder context: [{}]",
                directory);
            return "Unknown Time";
        }
    }

    /**
     * Looks inside a session directory, finds the primary chat data JSON file, and compiles the
     * Chat Group Name alongside its history coverage timeline window.
     */
    private String buildChatDisplayTitle(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            Optional<Path> jsonFileOpt = files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                .findFirst();

            if (jsonFileOpt.isEmpty()) {
                return directory.getFileName().toString() + " (Empty Context)";
            }

            Path jsonFile = jsonFileOpt.get();
            String rawFileName = jsonFile.getFileName().toString();
            String fallbackTitle = rawFileName.substring(0,
                rawFileName.toLowerCase().lastIndexOf(".json"));

            try {
                JsonNode rootNode = objectMapper.readTree(jsonFile.toFile());
                JsonNode sessionNode = rootNode.get("session");

                if (sessionNode != null) {
                    String chatName =
                        sessionNode.has("nickname") && !sessionNode.get("nickname").asText()
                            .isEmpty()
                            ? sessionNode.get("nickname").asText()
                            : fallbackTitle;

                    long firstTime = sessionNode.path("firstTimestamp").asLong(0);
                    long lastTime = sessionNode.path("lastTimestamp").asLong(0);

                    if (firstTime > 0 && lastTime > 0) {
                        String startDate = CHAT_DATE_FORMATTER.format(
                            Instant.ofEpochSecond(firstTime));
                        String endDate = CHAT_DATE_FORMATTER.format(
                            Instant.ofEpochSecond(lastTime));
                        return String.format("%s (%s ~ %s)", chatName, startDate, endDate);
                    }
                    return chatName;
                }
                return fallbackTitle;
            } catch (Exception jsonErr) {
                log.warn("Metadata structure error inside [{}], dropping back to clean filename.",
                    rawFileName);
                return fallbackTitle;
            }
        } catch (IOException e) {
            log.error("Failed to read system folder layers inside: [{}]", directory, e);
            return directory.getFileName().toString() + " (Read Failure)";
        }
    }
}