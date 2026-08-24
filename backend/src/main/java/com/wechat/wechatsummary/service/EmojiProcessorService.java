package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.ProcessingConfig;
import com.wechat.wechatsummary.entity.EmojiSummaryEntity;
import com.wechat.wechatsummary.util.HashUtils;
import com.wechat.wechatsummary.util.PageUtils;
import com.wechat.wechatsummary.util.PathUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Service orchestration class responsible for managing the end-to-end animated sticker (emoji)
 * processing lifecycle. Animated stickers are persisted in a dedicated {@code emoji_summary} table
 * so they can be reviewed independently from static images.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmojiProcessorService {

    private final AiService emojiAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final MediaFileResourceLoader fileResourceLoader;
    private final StoragePaths storagePaths;
    private final ProcessingConfig processingConfig;

    public void processImage(String filePath) {
        log.info("Initiating emoji processing pipeline execution for target file: [{}]", filePath);
        try {
            // Stable, environment-independent key (see ImageProcessorService for rationale).
            Path absolute = Paths.get(filePath).toAbsolutePath().normalize();
            String hash = HashUtils.sha256(storagePaths.uploadRoot().relativize(absolute).toString());

            Optional<EmojiSummaryEntity> dbRecord = cacheService.findEmojiSummaryByHash(hash);

            if (dbRecord.isPresent()) {
                log.info(
                    "Trace match hit for emoji hash: [{}]. Skipping duplicate AI processing for file: {}",
                    hash, filePath);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Emoji file validation failed. Target resource does not exist on disk: {}",
                    filePath);
                return;
            }

            byte[] emojiBytes = Files.readAllBytes(path);

            if (emojiBytes.length > processingConfig.getImageMaxSizeBytes()) {
                log.warn(
                    "Emoji analysis aborted. Payload size ({} bytes) exceeds the allowed {} byte structural limit for file: {}",
                    emojiBytes.length, processingConfig.getImageMaxSizeBytes(), filePath);
                return;
            }

            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/png";
            }

            String summary = emojiAiSummaryService.generateSummary(emojiBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn("AI multimodal vision analysis returned a blank summary for emoji file: {}",
                    filePath);
                return;
            }

            EmojiSummaryEntity entity = new EmojiSummaryEntity();
            entity.setId(hash);
            entity.setEmojiHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            cacheService.saveEmojiSummary(entity);
            log.info(
                "Emoji processing pipeline executed successfully. Record persisted for hash: [{}]",
                hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing emoji resource context: {}",
                filePath, e);
            // Re-throw so the media listener can requeue the message with a delay and retry
            // instead of silently dropping the summary (e.g. on transient AI 429/5xx errors).
            throw new RuntimeException("Emoji processing failed for " + filePath, e);
        }
    }

    public Page<EmojiSummaryEntity> getEmojiSummariesByUuid(String uuid, Pageable pageable) {
        List<EmojiSummaryEntity> entities = cacheService.getEmojiSummariesByUuid(uuid);

        List<EmojiSummaryEntity> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(
                entity -> PathUtils.extractTimestamp(entity.getFilePath())))
            .toList();

        return PageUtils.paginate(processedList, pageable);
    }

    private EmojiSummaryEntity sanitizeFilePath(EmojiSummaryEntity original, String uuid) {
        EmojiSummaryEntity sanitized = new EmojiSummaryEntity();
        sanitized.setId(original.getId());
        sanitized.setEmojiHash(original.getEmojiHash());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());
        sanitized.setFilePath(PathUtils.relativizeToUuid(original.getFilePath(), uuid));
        return sanitized;
    }

    public void deleteEmojiSummaryById(String id) {
        cacheService.findEmojiSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteEmojiSummaryById(id);
    }

    public void deleteEmojiSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findEmojiSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteEmojiSummariesByIds(ids);
    }

    private void invalidateProcessedMarkdown(EmojiSummaryEntity entity) {
        if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
            return;
        }
        storagePaths.deleteProcessedMarkdownFor(Paths.get(entity.getFilePath()));
    }

    /**
     * Deletes all emoji description records associated with a specific session UUID.
     *
     * @param uuid target session/chat identifier
     */
    public void deleteAllEmojiSummariesByUuid(String uuid) {
        log.info("Deleting all emoji summaries for session UUID: [{}]", uuid);
        List<EmojiSummaryEntity> entities = cacheService.getEmojiSummariesByUuid(uuid);
        if (entities != null && !entities.isEmpty()) {
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream()
                .map(EmojiSummaryEntity::getId)
                .toList();
            cacheService.deleteEmojiSummariesByIds(ids);
        }
    }

    public Optional<MediaFileResourceLoader.MediaFileResource> getEmojiFileById(String id) {
        return cacheService.findEmojiSummaryByHash(id)
            .map(EmojiSummaryEntity::getFilePath)
            .flatMap(path -> fileResourceLoader.load(path, MediaType.IMAGE_PNG_VALUE));
    }
}
