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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageProcessorService {

    @Qualifier("multimodalChatClient")
    private final ChatClient chatClient;
    private final ImageSummaryCacheService cacheService;
    private final ImageSummaryRepository imageSummaryRepository;

    private final RetryTemplate aiRetryTemplate = RetryTemplate.builder()
        .maxAttempts(4)
        .exponentialBackoff(2000, 2.0, 10000)
        .retryOn(Exception.class)
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

            String hash = sha256(filePath);

            // =======================================================
            // 💡 1 & 2. 自动化缓存拦截（Redis Hit 直接返回 / DB Hit 自动回填 Redis 并在下一行返回）
            // =======================================================
            Optional<String> existingSummary = cacheService.getSummary(hash);
            if (existingSummary.isPresent()) {
                log.info("【命中缓存/持久层】图片已被处理过，无需调用 AI. File: {}", filePath);
                return; // 直接结束，完美拦截
            }

            // =======================================================
            // 3. AI PROCESSING (缓存未命中，开始昂贵的 AI 算力调用)
            // =======================================================
            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                mimeType = "image/jpeg";
            }
            String finalMimeType = mimeType;

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

            // =======================================================
            // 4. 保存到 Postgres (Source of Truth)
            // =======================================================
            ImageSummaryEntity entity = new ImageSummaryEntity();
            entity.setId(hash);
            entity.setImageHash(hash);
            entity.setFilePath(filePath);
            entity.setSummary(summary);
            entity.setCreatedAt(Instant.now());

            imageSummaryRepository.save(entity);

            // =======================================================
            // 5. 同步写入 Redis 缓存
            // =======================================================
            cacheService.putSummary(hash, summary);

            log.info("Processed image successfully: {}", filePath);

        } catch (Exception e) {
            log.error("Failed to process image permanently: {}", filePath, e);
        }
    }

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