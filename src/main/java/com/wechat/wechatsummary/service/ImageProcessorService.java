package com.wechat.wechatsummary.service;

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

    /**
     * Processes an image file based on strict record states:
     * <ol>
     * <li>If record exists: Skipping duplicate processing immediately.</li>
     * <li>If record is missing: Validates payload, runs Multimodal AI Vision, and persists entity.</li>
     * </ol>
     *
     * @param filePath system path pointing to the targeted image file resource
     */
    public void processImage(String filePath) {
        log.info("Initiating image processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = sha256(filePath);

            // Record Lookup through Cache Service
            Optional<ImageSummaryEntity> dbRecord = cacheService.findImageSummaryByHash(hash);

            // Strict State Check: If record exists, skip processing completely
            if (dbRecord.isPresent()) {
                log.info("Trace match hit for image hash: [{}]. Skipping duplicate AI processing for file: {}",
                    hash, filePath);
                return;
            }

            // Record is missing -> Proceed with Validation and AI Execution
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Image file validation failed. Target resource does not exist on disk: {}", filePath);
                return;
            }

            byte[] imageBytes = Files.readAllBytes(path);

            // Constraint Check: 5MB maximum file payload ceiling
            if (imageBytes.length > 5_000_000) {
                log.warn("Image analysis aborted. Payload size ({} bytes) exceeds the allowed 5MB structural limit for file: {}",
                    imageBytes.length, filePath);
                return;
            }

            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
                if (log.isDebugEnabled()) {
                    log.debug("Probed MimeType resolved to null for file {}. Defaulting fallback header to image/jpeg.", filePath);
                }
            }

            log.info("Cache/DB miss for image [{}]. Requesting multimodal vision summary from AI service...", hash);
            String summary = imageAiSummaryService.generateSummary(imageBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn("AI multimodal vision analysis returned a blank or empty summary layout text contract for file: {}", filePath);
                return;
            }

            // Persistence Phase via Cache Service Abstraction
            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            cacheService.saveImageSummary(entity);
            log.info("Image processing pipeline executed successfully. Record persisted for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception or structural IO crash encountered while processing image resource context: {}", filePath, e);
        }
    }

    /**
     * Retrieves image description records for a specific chat/session UUID,
     * sanitized to hide full path structures, sorted by image timestamp extracted from the path,
     * and paginated according to the provided Pageable options.
     *
     * @param uuid unique target session/chat identifier
     * @param pageable pagination parameters (page number and page size)
     * @return Page of {@link ImageSummaryEntity} records scoped to the provided UUID
     */
    public Page<ImageSummaryEntity> getImageSummariesByUuid(String uuid, Pageable pageable) {
        log.info("Requesting image summary records for session UUID: [{}] (page: {}, size: {})",
            uuid, pageable.getPageNumber(), pageable.getPageSize());

        // 1. Fetch full list (cached or DB)
        List<ImageSummaryEntity> entities = cacheService.getImageSummariesByUuid(uuid);

        // 2. Sanitize file paths & sort by image path timestamp
        List<ImageSummaryEntity> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(this::extractImageTimestamp))
            .toList();

        // 3. Slice list for pagination
        int total = processedList.size();
        int start = (int) pageable.getOffset();

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int end = Math.min(start + pageable.getPageSize(), total);
        List<ImageSummaryEntity> pageContent = processedList.subList(start, end);

        return new PageImpl<>(pageContent, pageable, total);
    }

    /**
     * Creates a shallow copy of ImageSummaryEntity with a relative filePath.
     * e.g., converts "/Users/.../uploads/{uuid}/emojis/20260618/file.gif"
     * to "emojis/20260618/file.gif"
     */
    private ImageSummaryEntity sanitizeFilePath(ImageSummaryEntity original, String uuid) {
        ImageSummaryEntity sanitized = new ImageSummaryEntity();
        sanitized.setId(original.getId());
        sanitized.setImageHash(original.getImageHash());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());

        String rawPath = original.getFilePath();
        if (rawPath != null && !rawPath.isBlank()) {
            // Find the boundary right after "/{uuid}/"
            String targetSegment = "/" + uuid + "/";
            int index = rawPath.indexOf(targetSegment);

            if (index != -1) {
                // Extract everything after "/{uuid}/"
                sanitized.setFilePath(rawPath.substring(index + targetSegment.length()));
            } else {
                sanitized.setFilePath(rawPath);
            }
        }

        return sanitized;
    }

    /**
     * Extracts the Unix timestamp integer from paths like:
     * "emojis/20260618/1781776986_64810f0a4a76b31ee5e0bfcd2d97ce33.gif"
     */
    private long extractImageTimestamp(ImageSummaryEntity entity) {
        if (entity.getFilePath() == null) {
            return 0L;
        }
        try {
            // Gets the filename part: "1781776986_64810f0a4a76b31ee5e0bfcd2d97ce33.gif"
            String fileName = Paths.get(entity.getFilePath()).getFileName().toString();
            // Takes everything before the first underscore: "1781776986"
            String timestampStr = fileName.split("_")[0];
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            log.warn("Failed to parse image creation timestamp from path: {}. Falling back to 0.", entity.getFilePath());
            return 0L;
        }
    }

    /**
     * Deletes a single image description record by its ID (hash).
     *
     * @param id target entity ID (image hash) to delete
     */
    public void deleteImageSummaryById(String id) {
        cacheService.deleteImageSummaryById(id);
    }

    /**
     * Deletes multiple image description records by their IDs (hashes).
     *
     * @param ids list of target entity IDs (image hashes) to delete
     */
    public void deleteImageSummariesByIds(List<String> ids) {
        cacheService.deleteImageSummariesByIds(ids);
    }

    /**
     * Computes the SHA-256 cryptographic digest hash string of the provided input text.
     *
     * @param input source text payload to hash
     * @return hexadecimal SHA-256 string representation
     * @throws RuntimeException if the SHA-256 messaging digest mechanism is unavailable
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Cryptographic configuration error initialization for SHA-256 failed.", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieves the image file resource and content type by record ID (hash).
     *
     * @param id target entity ID (image hash)
     * @return Optional containing an array with [Resource, MediaType String] if found and exists on disk
     */
    public Optional<ImageFileResource> getImageFileById(String id) {
        log.info("Fetching image file resource for ID: [{}]", id);
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

    /**
     * Simple record wrapper for image resource response payload.
     */
    public record ImageFileResource(Resource resource, String contentType) {}
}