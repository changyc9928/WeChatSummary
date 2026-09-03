package com.wechat.wechatsummary.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.dto.SessionResponseDTO;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * directory-stripping logic, and line-ending normalizations isolated per user UUID.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZipExtractionService {

    private static final DateTimeFormatter CHAT_DATE_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter UPLOAD_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "yyyy/MM/dd HH:mm")
        .withZone(ZoneId.systemDefault());
    private final StoragePaths storagePaths;
    private final ObjectMapper objectMapper;

    /**
     * Stashes a multipart form upload file onto a temporary location, initializes a tracking UUID
     * inside the user's UUID-specific directory subspace, extracts archive data sets safely, and
     * recycles dumps.
     *
     * @param userId the user's UUID primary key
     * @param file   the raw multipart archive bundle resource provided by HTTP client requests
     * @return unique tracking string token assigned to the resulting output execution workspace
     * @throws IOException if directory access permissions fail or unpacking exceptions interrupt
     *                     operations
     */
    public String upload(String userId, MultipartFile file) throws IOException {
        Path userDir = storagePaths.userDir(userId);
        Files.createDirectories(userDir);

        Path tempZip = Files.createTempFile("upload-", ".zip");
        file.transferTo(tempZip);

        String uuid = UUID.randomUUID().toString();
        Path extractDir = storagePaths.sessionDir(userId, uuid);

        log.info(
            "Successfully received uploaded file [{}]. Generated processing session UUID: [{}], unpacking target path: [{}] for user UUID: [{}]",
            file.getOriginalFilename(), uuid, extractDir.toAbsolutePath(), userId);

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
     * Two-phase extraction: first reads all entries into memory to detect archive-level structure,
     * then extracts using the determined path mapping. This avoids the per-entry
     * stripFirstDirectory bug that incorrectly flattens legitimate nested directories.
     */
    private void extractZipSafely(Path zipFile, Path targetDir) throws IOException {
        List<ZipEntryData> entries = readZipEntries(zipFile);

        Optional<String> redundantRoot = detectRedundantRootDirectory(entries);

        if (log.isDebugEnabled()) {
            redundantRoot.ifPresent(root -> log.debug(
                "Detected redundant root directory wrapper [{}] in archive, will flatten one level",
                root));
        }

        for (ZipEntryData entry : entries) {
            String entryName = normalizeZipEntryName(entry.name());

            validateZipEntryPath(entry.name(), targetDir);

            String extractionName = redundantRoot
                .map(root -> removeRedundantRootDirectory(entryName, root))
                .orElse(entryName);

            if (extractionName.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Skipping root folder entry footprint segment during extraction path evaluation: {}",
                        entry.name());
                }
                continue;
            }

            Path resolvedPath = targetDir.resolve(extractionName).normalize();

            if (!resolvedPath.startsWith(targetDir)) {
                log.error(
                    "Security boundary violation detected! Malicious path manipulation found in file entry: {}",
                    entry.name());
                throw new IOException("Bad zip entry path trajectory: " + entry.name());
            }

            if (entry.directory()) {
                Files.createDirectories(resolvedPath);
            } else {
                Files.createDirectories(resolvedPath.getParent());
                Files.write(resolvedPath, entry.content(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                normalizeTextFile(resolvedPath);
            }
        }
    }

    private List<ZipEntryData> readZipEntries(Path zipFile) throws IOException {
        List<ZipEntryData> entries = new ArrayList<>();
        try (
            InputStream fis = Files.newInputStream(zipFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)
        ) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                boolean isDirectory = entry.isDirectory();
                byte[] content = isDirectory ? new byte[0] : zis.readAllBytes();
                entries.add(new ZipEntryData(entry.getName(), isDirectory, content));
            }
        }
        return entries;
    }

    /**
     * Detects whether the archive has a redundant root wrapper directory. A redundant root exists
     * when there is exactly one top-level directory and all meaningful content is nested inside an
     * identically-named child directory (e.g., xxx/xxx/aaa.txt).
     *
     * <p>The structural rule: flatten root/root/... only when there is exactly one meaningful
     * top-level root and all meaningful content is contained beneath the identically named child
     * directory.</p>
     */
    private Optional<String> detectRedundantRootDirectory(List<ZipEntryData> entries) {
        Set<String> topLevelComponents = new HashSet<>();

        for (ZipEntryData entry : entries) {
            String name = normalizeZipEntryName(entry.name());

            if (name.isEmpty()) {
                continue;
            }

            int slash = name.indexOf('/');
            if (slash < 0) {
                // A root-level file means this is not a single directory wrapper.
                return Optional.empty();
            }
            topLevelComponents.add(name.substring(0, slash));
        }

        if (topLevelComponents.size() != 1) {
            return Optional.empty();
        }

        String root = topLevelComponents.iterator().next();
        String duplicatedRootPrefix = root + "/" + root + "/";

        boolean foundNestedContent = false;

        for (ZipEntryData entry : entries) {
            String name = normalizeZipEntryName(entry.name());

            if (name.isEmpty() || name.equals(root + "/") || name.equals(root)) {
                continue;
            }

            if (!name.startsWith(duplicatedRootPrefix)) {
                return Optional.empty();
            }

            String remainder = name.substring(duplicatedRootPrefix.length());
            if (!remainder.isEmpty()) {
                foundNestedContent = true;
            }
        }

        return foundNestedContent ? Optional.of(root) : Optional.empty();
    }

    private String removeRedundantRootDirectory(String path, String redundantRoot) {
        String prefix = redundantRoot + "/";

        if (path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }

        return path;
    }

    private String normalizeZipEntryName(String name) {
        return name.replace('\\', '/')
            .replaceAll("/+", "/")
            .replaceFirst("^\\.?/", "");
    }

    private void validateZipEntryPath(String entryName, Path targetDir) throws IOException {
        String rawForwardSlashed = entryName.replace('\\', '/');
        if (rawForwardSlashed.startsWith("/") || rawForwardSlashed.matches("^[a-zA-Z]:.*")) {
            throw new IOException("Bad zip entry path trajectory: " + entryName);
        }

        String normalized = normalizeZipEntryName(entryName);

        if (normalized.isEmpty()) {
            return;
        }

        for (String component : normalized.split("/")) {
            if (component.equals("..")) {
                throw new IOException("Bad zip entry path trajectory: " + entryName);
            }
        }

        Path entryPath = Path.of(normalized).normalize();
        Path resolved = targetDir.resolve(entryPath).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IOException("Bad zip entry path trajectory: " + entryName);
        }
    }

    private void normalizeTextFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            content = content.replace("\r\n", "\n");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /**
     * Scans the specific user's UUID subdirectory for active session subdirectories. Returns sorted
     * SessionResponseDTO objects with separated upload timestamps.
     */
    public List<SessionResponseDTO> listAvailableSessions(String userId) throws IOException {
        Path userDir = storagePaths.userDir(userId);
        if (!Files.exists(userDir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(userDir)) {
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
                ChatMetadata metadata = readChatMetadata(jsonFile);
                String chatName = metadata.nickname() != null && !metadata.nickname().isEmpty()
                    ? metadata.nickname()
                    : fallbackTitle;

                long minTs = metadata.minTimestamp();
                long maxTs = metadata.maxTimestamp();

                if (minTs > 0 && maxTs > 0) {
                    String startDate = CHAT_DATE_FORMATTER.format(
                        Instant.ofEpochSecond(minTs));
                    String endDate = CHAT_DATE_FORMATTER.format(
                        Instant.ofEpochSecond(maxTs));
                    return String.format("%s (%s ~ %s)", chatName, startDate, endDate);
                }
                return chatName;
            } catch (Exception jsonErr) {
                log.warn("Metadata structure error inside [{}], dropping back to clean filename.",
                    rawFileName, jsonErr);
                return fallbackTitle;
            }
        } catch (IOException e) {
            log.error("Failed to read system folder layers inside: [{}]", directory, e);
            return directory.getFileName().toString() + " (Read Failure)";
        }
    }

    private ChatMetadata readChatMetadata(Path jsonFile) throws IOException {
        String nickname = null;
        long firstTimestamp = 0;
        long lastTimestamp = 0;
        boolean hasValidOldTimestamps = false;
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;

        try (JsonParser parser = objectMapper.getFactory().createParser(jsonFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return new ChatMetadata(nickname, 0, 0);
            }

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken token = parser.nextToken();

                switch (fieldName) {
                    case "session":
                        if (token == JsonToken.START_OBJECT) {
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                String sessionField = parser.currentName();
                                JsonToken sessionToken = parser.nextToken();
                                switch (sessionField) {
                                    case "nickname":
                                        if (sessionToken == JsonToken.VALUE_STRING) {
                                            nickname = parser.getValueAsString();
                                        }
                                        break;
                                    case "firstTimestamp":
                                        if (sessionToken == JsonToken.VALUE_NUMBER_INT) {
                                            firstTimestamp = parser.getLongValue();
                                        }
                                        break;
                                    case "lastTimestamp":
                                        if (sessionToken == JsonToken.VALUE_NUMBER_INT) {
                                            lastTimestamp = parser.getLongValue();
                                        }
                                        break;
                                    default:
                                        if (sessionToken == JsonToken.START_OBJECT
                                            || sessionToken == JsonToken.START_ARRAY) {
                                            parser.skipChildren();
                                        }
                                        break;
                                }
                            }
                            hasValidOldTimestamps = firstTimestamp > 0 && lastTimestamp > 0;
                        }
                        break;
                    case "messages":
                        if (token == JsonToken.START_ARRAY) {
                            if (!hasValidOldTimestamps) {
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                                            String msgField = parser.currentName();
                                            JsonToken msgToken = parser.nextToken();
                                            if ("createTime".equals(msgField)
                                                && (msgToken == JsonToken.VALUE_NUMBER_INT
                                                || msgToken == JsonToken.VALUE_NUMBER_FLOAT)) {
                                                long ct = parser.getLongValue();
                                                if (ct > 0) {
                                                    if (ct < minTimestamp) {
                                                        minTimestamp = ct;
                                                    }
                                                    if (ct > maxTimestamp) {
                                                        maxTimestamp = ct;
                                                    }
                                                }
                                            } else {
                                                if (msgToken == JsonToken.START_OBJECT
                                                    || msgToken == JsonToken.START_ARRAY) {
                                                    parser.skipChildren();
                                                }
                                            }
                                        }
                                    } else if (parser.currentToken() != JsonToken.END_ARRAY) {
                                        if (parser.currentToken() == JsonToken.START_ARRAY
                                            || parser.currentToken() == JsonToken.START_OBJECT) {
                                            parser.skipChildren();
                                        }
                                    }
                                }
                            } else {
                                parser.skipChildren();
                            }
                        }
                        break;
                    default:
                        if (token == JsonToken.START_OBJECT
                            || token == JsonToken.START_ARRAY) {
                            parser.skipChildren();
                        }
                        break;
                }
            }
        }

        if (hasValidOldTimestamps) {
            return new ChatMetadata(nickname, firstTimestamp, lastTimestamp);
        }

        if (minTimestamp == Long.MAX_VALUE || maxTimestamp == Long.MIN_VALUE) {
            log.debug("JSON file [{}] has no valid createTime values in messages array.",
                jsonFile.getFileName());
            return new ChatMetadata(nickname, 0, 0);
        }

        return new ChatMetadata(nickname, minTimestamp, maxTimestamp);
    }

    private record ChatMetadata(String nickname, long minTimestamp, long maxTimestamp) {

    }

    private record ZipEntryData(String name, boolean directory, byte[] content) {

    }
}