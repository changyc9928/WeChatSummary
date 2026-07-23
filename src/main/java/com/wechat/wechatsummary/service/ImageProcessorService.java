package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service orchestration class responsible for managing the end-to-end image processing lifecycle.
 * Relies directly on DB lookups to execute Vision AI processing and triggers cache invalidation upon success.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageProcessorService {

    private final AiService imageAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final ImageSummaryRepository imageSummaryRepository;

    /**
     * Processes an image file based on strict record states:
     * <ol>
     * <li>If DB record exists: Skipping duplicate processing immediately.</li>
     * <li>If DB record is missing: Validates payload, runs Multimodal AI Vision, saves to DB, and evicts cache.</li>
     * </ol>
     *
     * @param filePath system path pointing to the targeted image file resource
     */
    public void processImage(String filePath) {
        log.info("Initiating image processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = sha256(filePath);

            // Database Lookup Only (Cache lookups removed)
            Optional<ImageSummaryEntity> dbRecord = imageSummaryRepository.findByImageHash(hash);

            // Strict State Check: If DB record exists, skip processing completely
            if (dbRecord.isPresent()) {
                log.info("Database trace match hit for image hash: [{}]. Skipping duplicate AI processing for file: {}",
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

            log.info("Database miss for image [{}]. Requesting multimodal vision summary from AI service...", hash);
            String summary = imageAiSummaryService.generateSummary(imageBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn("AI multimodal vision analysis returned a blank or empty summary layout text contract for file: {}", filePath);
                return;
            }

            // Persistence Phase
            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            imageSummaryRepository.save(entity);

            // Invalidate cache following successful database update
            cacheService.evictImageSummary(hash);
            log.info("Image processing pipeline executed successfully. Record saved and cache evicted for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception or structural IO crash encountered while processing image resource context: {}", filePath, e);
        }
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
}