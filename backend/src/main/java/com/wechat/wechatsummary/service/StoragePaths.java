package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the user-isolated on-disk storage layout:
 * {@code uploadDir / {userId} / {uuid} / ...} for session workspaces and
 * {@code uploadDir / {userId} / outputs} for compiled documents.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StoragePaths {

    private final StorageConfig storageConfig;

    public Path userDir(String userId) {
        return storageConfig.getUploadDir().resolve(userId);
    }

    /**
     * Absolute, normalized root under which all user session workspaces live. Used as the stable
     * base for environment-independent media hashing (so the same file yields the same key whether
     * processed inside the Docker container or on a developer's host machine).
     */
    public Path uploadRoot() {
        return storageConfig.getUploadDir().toAbsolutePath().normalize();
    }

    public Path sessionDir(String userId, String uuid) {
        return userDir(userId).resolve(uuid);
    }

    public Path outputDir(String userId) {
        return userDir(userId).resolve("outputs");
    }

    public Path processedMarkdown(String userId, String uuid) {
        return outputDir(userId).resolve(uuid + "_processed.md");
    }

    public Path summaryTxt(String userId, String uuid) {
        return outputDir(userId).resolve(uuid + "_summary.txt");
    }

    public Path summaryTemp(String userId, String uuid) {
        return outputDir(userId).resolve(uuid + "_summary.temp");
    }

    /**
     * Locates the raw chat export JSON sitting inside the session workspace
     * ({@code uploadDir / {userId} / {uuid} / *.json}). This is the source of truth for the
     * WeChat (talker) IDs that back the wxid-keyed identity registry. Returns {@code null} when no
     * such file exists.
     */
    public Path rawExportJson(String userId, String uuid) {
        Path dir = sessionDir(userId, uuid);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(path -> {
                    if (Files.isDirectory(path)) {
                        return false;
                    }
                    String name = path.getFileName().toString();
                    return name.endsWith(".json") && !name.equals(uuid + "_identities.json");
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Optional per-chat identity sidecar ({@code {uuid}_identities.json}) where the operator can
     * supply authoritative aliases and social-media handles, keyed by wxid (preferred) or by a
     * known display name. This overrides any auto-inferred alias mapping.
     */
    public Path identitiesFile(String userId, String uuid) {
        return sessionDir(userId, uuid).resolve(uuid + "_identities.json");
    }

    /**
     * Deletes the {@code {uuid}_processed.md} file belonging to the session that owns the given
     * media file path ({@code .../{userId}/{uuid}/images/... or /emojis/... or /voices/...}).
     * Returns the deleted markdown path, or {@code null} when nothing is found.
     */
    public Path deleteProcessedMarkdownFor(Path mediaFilePath) {
        if (mediaFilePath == null) {
            return null;
        }
        Path parent = mediaFilePath.getParent();
        while (parent != null) {
            Path userIdDir = parent.getParent();
            if (userIdDir != null && Files.exists(userIdDir.resolve("outputs"))) {
                String uuid = parent.getFileName().toString();
                Path processedMd = userIdDir.resolve("outputs").resolve(uuid + "_processed.md");
                if (Files.exists(processedMd)) {
                    try {
                        Files.delete(processedMd);
                        log.info(
                            "Successfully invalidated/deleted processed markdown file: [{}]",
                            processedMd);
                    } catch (Exception e) {
                        log.error("Failed to delete processed markdown file: [{}]", processedMd, e);
                    }
                    return processedMd;
                }
                return null;
            }
            parent = parent.getParent();
        }
        return null;
    }
}