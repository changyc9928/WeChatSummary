package com.wechat.wechatsummary.service;

import com.openai.models.audio.AudioResponseFormat;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.util.HashUtils;
import com.wechat.wechatsummary.util.PageUtils;
import com.wechat.wechatsummary.util.PathUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
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

    private static final String AUDIO_MIME_TYPE = "audio/mpeg";

    private final AiService audioAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final MediaFileResourceLoader fileResourceLoader;
    private final StoragePaths storagePaths;

    public void processAudioSummary(String filePath) {
        log.info("Initiating audio processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = HashUtils.sha256(filePath);

            Optional<AudioSummary> dbRecord = cacheService.findAudioSummaryByHash(hash);

            if (dbRecord.isPresent() && dbRecord.get().getSummary() != null && !dbRecord.get()
                .getSummary().isBlank()) {
                log.info("Trace match hit for audio hash: [{}]. Skipping duplicate AI processing.",
                    hash);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Audio processing aborted. Resource file does not exist at path: {}",
                    filePath);
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

            String summary = audioAiSummaryService.callChatClientToSummarizeAudioWithRetry(
                transcript);
            if (summary == null || summary.isBlank()) {
                log.warn("LLM audio summarization returned empty output for file: {}", filePath);
                return;
            }

            entity.setSummary(summary);
            cacheService.saveAudioSummary(entity);
            log.info("Audio processing pipeline executed successfully for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing audio resource context: {}",
                filePath, e);
        }
    }

    public Page<AudioSummary> getAudioSummariesByUuid(String uuid, Pageable pageable) {
        List<AudioSummary> entities = cacheService.getAudioSummariesByUuid(uuid);

        List<AudioSummary> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(
                entity -> PathUtils.extractTimestamp(entity.getFilePath())))
            .toList();

        return PageUtils.paginate(processedList, pageable);
    }

    private String executeTranscription(String filePath) {
        FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));
        String localAIPrompt = "这是一段马来西亚华人的日常Rojak华语语音对话。";

        var transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
            .model("large-v3")
            .prompt(localAIPrompt)
            .responseFormat(AudioResponseFormat.JSON)
            .build();

        AudioTranscriptionPrompt transcriptionPrompt = new AudioTranscriptionPrompt(audioResource,
            transcriptionOptions);
        var transcriptionResponse = audioAiSummaryService.callTranscriptionWithRetry(
            transcriptionPrompt);
        return transcriptionResponse.getResult().getOutput();
    }

    private AudioSummary sanitizeFilePath(AudioSummary original, String uuid) {
        AudioSummary sanitized = new AudioSummary();
        sanitized.setId(original.getId());
        sanitized.setFileHash(original.getFileHash());
        sanitized.setTranscript(original.getTranscript());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());
        sanitized.setFilePath(PathUtils.relativizeToUuid(original.getFilePath(), uuid));
        return sanitized;
    }

    public void deleteAudioSummaryById(String id) {
        cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteAudioSummaryById(id);
    }

    public void deleteAudioSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findAudioSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteAudioSummariesByIds(ids);
    }

    private void invalidateProcessedMarkdown(AudioSummary entity) {
        if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
            return;
        }
        storagePaths.deleteProcessedMarkdownFor(Paths.get(entity.getFilePath()));
    }

    public Optional<MediaFileResourceLoader.MediaFileResource> getAudioFileById(String id) {
        return cacheService.findAudioSummaryByHash(id)
            .map(AudioSummary::getFilePath)
            .flatMap(path -> fileResourceLoader.load(path, AUDIO_MIME_TYPE));
    }

    public void clearAudioSummaryTextById(String id) {
        cacheService.findAudioSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.clearAudioSummaryTextById(id);
    }

    public void clearAudioSummaryTextsByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findAudioSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
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
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream().map(AudioSummary::getId).toList();
            cacheService.deleteAudioSummariesByIds(ids);
        }
    }
}