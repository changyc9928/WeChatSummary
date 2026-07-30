package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
    private final StorageConfig storageConfig;

    public void processImage(String filePath) {
        log.info("Initiating image processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = sha256(filePath);

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

            if (imageBytes.length > 5_000_000) {
                log.warn(
                    "Image analysis aborted. Payload size ({} bytes) exceeds the allowed 5MB structural limit for file: {}",
                    imageBytes.length, filePath);
                return;
            }

            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
            }

            String summary = imageAiSummaryService.generateSummary(imageBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn("AI multimodal vision analysis returned a blank summary for file: {}", filePath);
                return;
            }

            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            cacheService.saveImageSummary(entity);
            log.info("Image processing pipeline executed successfully. Record persisted for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing image resource context: {}", filePath, e);
        }
    }

    public Page<ImageSummaryEntity> getImageSummariesByUuid(String uuid, Pageable pageable) {
        List<ImageSummaryEntity> entities = cacheService.getImageSummariesByUuid(uuid);

        List<ImageSummaryEntity> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(this::extractImageTimestamp))
            .toList();

        int total = processedList.size();
        int start = (int) pageable.getOffset();

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int end = Math.min(start + pageable.getPageSize(), total);
        List<ImageSummaryEntity> pageContent = processedList.subList(start, end);

        return new PageImpl<>(pageContent, pageable, total);
    }

    private ImageSummaryEntity sanitizeFilePath(ImageSummaryEntity original, String uuid) {
        ImageSummaryEntity sanitized = new ImageSummaryEntity();
        sanitized.setId(original.getId());
        sanitized.setImageHash(original.getImageHash());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());

        String rawPath = original.getFilePath();
        if (rawPath != null && !rawPath.isBlank()) {
            String targetSegment = "/" + uuid + "/";
            int index = rawPath.indexOf(targetSegment);

            if (index != -1) {
                sanitized.setFilePath(rawPath.substring(index + targetSegment.length()));
            } else {
                sanitized.setFilePath(rawPath);
            }
        }

        return sanitized;
    }

    private long extractImageTimestamp(ImageSummaryEntity entity) {
        if (entity.getFilePath() == null) {
            return 0L;
        }
        try {
            String fileName = Paths.get(entity.getFilePath()).getFileName().toString();
            String timestampStr = fileName.split("_")[0];
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            log.warn("Failed to parse image timestamp from path: {}", entity.getFilePath());
            return 0L;
        }
    }

    public void deleteImageSummaryById(String id) {
        cacheService.findImageSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteImageSummaryById(id);
    }

    public void deleteImageSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findImageSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteImageSummariesByIds(ids);
    }

    /**
     * Invalidates (deletes) the {uuid}_processed.md file linked to this record's storage path.
     */
    private void invalidateProcessedMarkdown(ImageSummaryEntity entity) {
        try {
            String filePath = entity.getFilePath();
            if (filePath == null || filePath.isBlank()) return;

            Path path = Paths.get(filePath);
            Path parent = path.getParent();

            // Walk up to find the folder structure matching: .../{userId}/{uuid}/images/...
            while (parent != null) {
                Path userIdDir = parent.getParent();
                if (userIdDir != null && Files.exists(userIdDir.resolve("outputs"))) {
                    String uuid = parent.getFileName().toString();
                    Path processedMd = userIdDir.resolve("outputs").resolve(uuid + "_processed.md");
                    if (Files.exists(processedMd)) {
                        Files.delete(processedMd);
                        log.info("Successfully invalidated/deleted processed markdown file: [{}]", processedMd);
                    }
                    return;
                }
                parent = parent.getParent();
            }
        } catch (Exception e) {
            log.error("Failed to invalidate processed markdown file for image path: {}", entity.getFilePath(), e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("SHA-256 failed.", e);
            throw new RuntimeException(e);
        }
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
            // Pass the entity object directly, matching invalidateProcessedMarkdown(ImageSummaryEntity entity)
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream()
                .map(ImageSummaryEntity::getId)
                .toList();
            cacheService.deleteImageSummariesByIds(ids);
        }
    }

    public Optional<ImageFileResource> getImageFileById(String id) {
        return cacheService.findImageSummaryByHash(id)
            .map(ImageSummaryEntity::getFilePath)
            .map(Paths::get)
            .filter(Files::exists)
            .map(path -> {
                try {
                    Resource resource = new UrlResource(path.toUri());
                    String contentType = Files.probeContentType(path);
                    if (contentType == null) {
                        contentType = MediaType.IMAGE_JPEG_VALUE;
                    }
                    return new ImageFileResource(resource, contentType);
                } catch (Exception e) {
                    log.error("Failed to load image file resource at path: {}", path, e);
                    return null;
                }
            });
    }

    public record ImageFileResource(Resource resource, String contentType) {}
}