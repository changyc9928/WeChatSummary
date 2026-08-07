package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
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