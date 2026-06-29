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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageProcessorService {

    private final ChatClient chatClient;
    private final ImageSummaryCacheService cacheService;
    private final ImageSummaryRepository imageSummaryRepository;

    // 🧠 声明一个专用的重试模板
    private final RetryTemplate aiRetryTemplate = RetryTemplate.builder()
        .maxAttempts(4)          // 最多重试 4 次
        .exponentialBackoff(2000, 2.0, 10000) // 初始 2s, 每次翻倍 (2s -> 4s -> 8s), 最大 10s
        .retryOn(Exception.class) // 遇到任何异常（包括 429/503 抛出的异常）都重试
        .build();

    public void processImage(String filePath) {

        try {
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                log.warn("File does not exist: {}", filePath);
                return;
            }

            byte[] imageBytes = Files.readAllBytes(path);

            if (imageBytes.length > 5_000_000) {
                log.warn("Image too large: {}", filePath);
                return;
            }

            // 保留你原汁原味的设计：基于文件路径计算 Hash
            String hash = sha256(filePath);

            // =========================
            // 1. CACHE (Redis)
            // =========================
            var cached = cacheService.get(hash);
            if (cached.isPresent()) {
                log.info("Cache hit for image: {}", filePath);
                return;
            }

            // =========================
            // 2. DATABASE (Postgres - source of truth)
            // =========================
            ImageSummaryEntity dbResult = imageSummaryRepository.findByImageHash(hash);

            if (dbResult != null) {
                log.info("DB hit for image: {}", filePath);
                cacheService.put(hash, dbResult.getSummary());
                return;
            }

            // =========================
            // 3. AI PROCESSING (带 429/503 弹性重试)
            // =========================
            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
            }

            String finalMimeType = mimeType;

            // 使用重试模板包裹 AI 调用逻辑
            String summary = aiRetryTemplate.execute(context -> {
                if (context.getRetryCount() > 0) {
                    log.warn("AI API failed, retrying... Attempt #{}", context.getRetryCount() + 1);
                }

                var userMessage = new UserMessage(
                    "Describe this image in a short sentence. If there is text, extract all text accurately."
                );

                userMessage.getMedia().add(
                    Media.builder()
                        .mimeType(MimeType.valueOf(finalMimeType))
                        .data(imageBytes)
                        .build()
                );

                Prompt prompt = new Prompt(userMessage);
                ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

                if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                    throw new RuntimeException("AI returned an empty response structure");
                }

                return response.getResult().getOutput().getText();
            });

            if (summary == null || summary.isBlank()) {
                log.warn("AI returned blank text for file: {}", filePath);
                return;
            }

            // =========================
            // 4. SAVE TO POSTGRES
            // =========================
            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            imageSummaryRepository.save(entity);

            // =========================
            // 5. CACHE RESULT
            // =========================
            cacheService.put(hash, summary);

            log.info("Processed image successfully: {}", filePath);

        } catch (Exception e) {
            log.error("Failed to process image permanently: {}", filePath, e);
        }
    }

    // 保持你原有的路径 Hash 计算逻辑
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}