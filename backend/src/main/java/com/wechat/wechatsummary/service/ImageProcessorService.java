package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.ProcessingConfig;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
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
 * Service orchestration class responsible for managing the end-to-end image processing lifecycle.
 * Treats cacheService as the black-box abstraction for image record persistence and retrieval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageProcessorService {

    private final AiService imageAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final MediaFileResourceLoader fileResourceLoader;
    private final StoragePaths storagePaths;
    private final ProcessingConfig processingConfig;

    public void processImage(String filePath) {
        log.info("Initiating image processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = HashUtils.sha256(filePath);

            Optional<ImageSummaryEntity> dbRecord = cacheService.findImageSummaryByHash(hash);

            if (dbRecord.isPresent()) {
                log.info(
                    "Trace match hit for image hash: [{}]. Skipping duplicate AI processing for file: {}",
                    hash, filePath);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Image file validation failed. Target resource does not exist on disk: {}",
                    filePath);
                return;
            }

            byte[] imageBytes = Files.readAllBytes(path);

            if (imageBytes.length > processingConfig.getImageMaxSizeBytes()) {
                log.warn(
                    "Image analysis aborted. Payload size ({} bytes) exceeds the allowed {} byte structural limit for file: {}",
                    imageBytes.length, processingConfig.getImageMaxSizeBytes(), filePath);
                return;
            }

            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
            }

            String summary = imageAiSummaryService.generateSummary(imageBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn("AI multimodal vision analysis returned a blank summary for file: {}",
                    filePath);
                return;
            }

            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            cacheService.saveImageSummary(entity);
            log.info(
                "Image processing pipeline executed successfully. Record persisted for hash: [{}]",
                hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing image resource context: {}",
                filePath, e);
        }
    }

    public Page<ImageSummaryEntity> getImageSummariesByUuid(String uuid, Pageable pageable) {
        List<ImageSummaryEntity> entities = cacheService.getImageSummariesByUuid(uuid);

        List<ImageSummaryEntity> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(
                entity -> PathUtils.extractTimestamp(entity.getFilePath())))
            .toList();

        return PageUtils.paginate(processedList, pageable);
    }

    private ImageSummaryEntity sanitizeFilePath(ImageSummaryEntity original, String uuid) {
        ImageSummaryEntity sanitized = new ImageSummaryEntity();
        sanitized.setId(original.getId());
        sanitized.setImageHash(original.getImageHash());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());
        sanitized.setFilePath(PathUtils.relativizeToUuid(original.getFilePath(), uuid));
        return sanitized;
    }

    public void deleteImageSummaryById(String id) {
        cacheService.findImageSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteImageSummaryById(id);
    }

    public void deleteImageSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findImageSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteImageSummariesByIds(ids);
    }

    private void invalidateProcessedMarkdown(ImageSummaryEntity entity) {
        if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
            return;
        }
        storagePaths.deleteProcessedMarkdownFor(Paths.get(entity.getFilePath()));
    }

    /**
     * Deletes all image description records associated with a specific session UUID.
     *
     * @param uuid target session/chat identifier
     */
    public void deleteAllImageSummariesByUuid(String uuid) {
        log.info("Deleting all image summaries for session UUID: [{}]", uuid);
        List<ImageSummaryEntity> entities = cacheService.getImageSummariesByUuid(uuid);
        if (entities != null && !entities.isEmpty()) {
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream()
                .map(ImageSummaryEntity::getId)
                .toList();
            cacheService.deleteImageSummariesByIds(ids);
        }
    }

    public Optional<MediaFileResourceLoader.MediaFileResource> getImageFileById(String id) {
        return cacheService.findImageSummaryByHash(id)
            .map(ImageSummaryEntity::getFilePath)
            .flatMap(path -> fileResourceLoader.load(path, MediaType.IMAGE_JPEG_VALUE));
    }
}