package com.wechat.wechatsummary.service;

import com.openai.models.audio.AudioResponseFormat;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.entity.AudioSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Service orchestration class managing decoupled ASR and LLM lifecycles. Treats cacheService as the
 * black-box abstraction for audio record persistence and retrieval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioProcessorService {

    private final AiService audioAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final StorageConfig storageConfig;

    public void processAudioSummary(String filePath) {
        log.info("Initiating audio processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = sha256(filePath);

            Optional<AudioSummary> dbRecord = cacheService.findAudioSummaryByHash(hash);

            if (dbRecord.isPresent() && dbRecord.get().getSummary() != null && !dbRecord.get()
                .getSummary().isBlank()) {
                log.info("Trace match hit for audio hash: [{}]. Skipping duplicate AI processing.", hash);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Audio processing aborted. Resource file does not exist at path: {}", filePath);
                return;
            }

            String transcript;
            AudioSummary entity;

            if (dbRecord.isPresent()) {
                entity = dbRecord.get();
                transcript = entity.getTranscript();
            } else {
                transcript = executeTranscription(filePath);
                if (transcript == null || transcript.isBlank()) {
                    log.warn("ASR transcription returned empty result for file: {}", filePath);
                    return;
                }

                entity = new AudioSummary();
                entity.setId(hash);
                entity.setFileHash(hash);
                entity.setFilePath(filePath);
                entity.setTranscript(transcript);
                entity.setCreatedAt(LocalDateTime.now());
            }

            String summary = audioAiSummaryService.callChatClientToSummarizeAudioWithRetry(transcript);
            if (summary == null || summary.isBlank()) {
                log.warn("LLM audio summarization returned empty output for file: {}", filePath);
                return;
            }

            entity.setSummary(summary);
            cacheService.saveAudioSummary(entity);
            log.info("Audio processing pipeline executed successfully for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing audio resource context: {}", filePath, e);
        }
    }

    public Page<AudioSummary> getAudioSummariesByUuid(String uuid, Pageable pageable) {
        List<AudioSummary> entities = cacheService.getAudioSummariesByUuid(uuid);

        List<AudioSummary> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(this::extractAudioTimestamp))
            .toList();

        int total = processedList.size();
        int start = (int) pageable.getOffset();

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int end = Math.min(start + pageable.getPageSize(), total);
        List<AudioSummary> pageContent = processedList.subList(start, end);

        return new PageImpl<>(pageContent, pageable, total);
    }

    private String executeTranscription(String filePath) {
        FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));
        String localAIPrompt = "这是一段马来西亚华人的日常Rojak华语语音对话。";

        var transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
            .model("large-v3")
            .prompt(localAIPrompt)
            .responseFormat(AudioResponseFormat.JSON)
            .build();

        AudioTranscriptionPrompt transcriptionPrompt = new AudioTranscriptionPrompt(audioResource, transcriptionOptions);
        var transcriptionResponse = audioAiSummaryService.callTranscriptionWithRetry(transcriptionPrompt);
        return transcriptionResponse.getResult().getOutput();
    }

    private AudioSummary sanitizeFilePath(AudioSummary original, String uuid) {
        AudioSummary sanitized = new AudioSummary();
        sanitized.setId(original.getId());
        sanitized.setFileHash(original.getFileHash());
        sanitized.setTranscript(original.getTranscript());
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

    private long extractAudioTimestamp(AudioSummary entity) {
        if (entity.getFilePath() == null) {
            return 0L;
        }
        try {
            String fileName = Paths.get(entity.getFilePath()).getFileName().toString();
            String timestampStr = fileName.split("_")[0];
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            return 0L;
        }
    }

    public void deleteAudioSummaryById(String id) {
        cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteAudioSummaryById(id);
    }

    public void deleteAudioSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteAudioSummariesByIds(ids);
    }

    /**
     * Invalidates (deletes) the {uuid}_processed.md file linked to this audio storage path.
     */
    private void invalidateProcessedMarkdown(AudioSummary entity) {
        try {
            String filePath = entity.getFilePath();
            if (filePath == null || filePath.isBlank()) return;

            Path path = Paths.get(filePath);
            Path parent = path.getParent();

            while (parent != null) {
                Path userIdDir = parent.getParent();
                if (userIdDir != null && Files.exists(userIdDir.resolve("outputs"))) {
                    String uuid = parent.getFileName().toString();
                    Path processedMd = userIdDir.resolve("outputs").resolve(uuid + "_processed.md");
                    if (Files.exists(processedMd)) {
                        Files.delete(processedMd);
                        log.info("Successfully invalidated/deleted processed markdown file for audio: [{}]", processedMd);
                    }
                    return;
                }
                parent = parent.getParent();
            }
        } catch (Exception e) {
            log.error("Failed to invalidate processed markdown file for audio path: {}", entity.getFilePath(), e);
        }
    }

    public Optional<AudioFileResource> getAudioFileById(String id) {
        return cacheService.findAudioSummaryByHash(id)
            .map(AudioSummary::getFilePath)
            .map(Paths::get)
            .filter(Files::exists)
            .map(path -> {
                try {
                    Resource resource = new UrlResource(path.toUri());
                    String contentType = Files.probeContentType(path);
                    if (contentType == null) {
                        contentType = "audio/mpeg";
                    }
                    return new AudioFileResource(resource, contentType);
                } catch (Exception e) {
                    log.error("Failed to load audio file resource at path: {}", path, e);
                    return null;
                }
            });
    }

    public void clearAudioSummaryTextById(String id) {
        cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.clearAudioSummaryTextById(id);
    }

    public void clearAudioSummaryTextsByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.clearAudioSummaryTextsByIds(ids);
    }

    /**
     * Deletes all audio summary records associated with a specific session UUID.
     *
     * @param uuid target session/chat identifier
     */
    public void deleteAllAudioSummariesByUuid(String uuid) {
        log.info("Deleting all audio summaries for session UUID: [{}]", uuid);
        List<AudioSummary> entities = cacheService.getAudioSummariesByUuid(uuid);
        if (entities != null && !entities.isEmpty()) {
            // Invalidate _processed.md using the first available valid file path context
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream().map(AudioSummary::getId).toList();
            cacheService.deleteAudioSummariesByIds(ids);
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

    public record AudioFileResource(Resource resource, String contentType) {}
}