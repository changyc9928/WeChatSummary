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
 * Coordinates validation, lookup checks between the cache and persistence layers, and coordinates
 * multimodal AI vision requests for image description and text extraction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageProcessorService {

    private final AiService imageAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final ImageSummaryRepository imageSummaryRepository;

    /**
     * Processes an image file by checking caches, parsing data payloads, probing media types,
     * routing payloads to multimodal AI frameworks, and securely persisting results.
     *
     * <p>Gracefully catches and logs unexpected pipeline exceptions to prevent runtime cascading
     * failures.</p>
     *
     * @param filePath system path pointing to the targeted image file resource
     */
    public void processImage(String filePath) {
        log.info("Initiating image processing pipeline execution for target file: [{}]", filePath);
        try {
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                log.warn("Image file validation failed. Target resource does not exist on disk: {}",
                    filePath);
                return;
            }

            byte[] imageBytes = Files.readAllBytes(path);

            // Constraint Check: 5MB maximum file payload ceiling
            if (imageBytes.length > 5_000_000) {
                log.warn(
                    "Image analysis aborted. Payload size ({} bytes) exceeds the allowed 5MB structural limit for file: {}",
                    imageBytes.length, filePath);
                return;
            }

            String hash = sha256(filePath);

            // Layered Cache & DB Verification Check
            Optional<String> existingSummary = cacheService.getImageSummary(hash);
            if (existingSummary.isPresent()) {
                log.info(
                    "Cache/Database trace match hit for image hash: [{}]. Skipping duplicate AI processing for file: {}",
                    hash, filePath);
                return;
            }

            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Probed MimeType resolved to null for file {}. Defaulting fallback header to image/jpeg.",
                        filePath);
                }
            }

            log.info(
                "Cache miss for image [{}]. Requesting multimodal vision summary from AI service...",
                hash);
            String summary = imageAiSummaryService.generateSummary(imageBytes, mimeType, filePath);

            if (summary == null || summary.isBlank()) {
                log.warn(
                    "AI multimodal vision analysis returned a blank or empty summary layout text contract for file: {}",
                    filePath);
                return;
            }

            // Persistence and Memory Mapping Storage Phase
            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            imageSummaryRepository.save(entity);
            cacheService.putImageSummary(hash, summary);

            log.info(
                "Image processing pipeline executed successfully. Record saved and cached for hash: [{}]",
                hash);

        } catch (Exception e) {
            log.error(
                "Fatal exception or structural IO crash encountered while processing image resource context: {}",
                filePath, e);
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