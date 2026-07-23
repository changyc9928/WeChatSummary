package com.wechat.wechatsummary.service;

import com.openai.models.audio.AudioResponseFormat;
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

    /**
     * Processes an audio file based on strict record states:
     * <ol>
     * <li>If DB record is missing: Runs ASR (Whisper) & LLM Summary, then persists.</li>
     * <li>If DB record exists with transcript & summary: Skips processing completely.</li>
     * </ol>
     *
     * @param filePath system path pointing to the audio source file
     */
    public void processAudioSummary(String filePath) {
        log.info("Initiating audio processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = sha256(filePath);

            // Record Lookup through Cache Service
            Optional<AudioSummary> dbRecord = cacheService.findAudioSummaryByHash(hash);

            // Case 1: Complete processing skip if both transcript and summary exist
            if (dbRecord.isPresent() && dbRecord.get().getSummary() != null && !dbRecord.get()
                .getSummary().isBlank()) {
                log.info(
                    "Trace match hit for audio hash: [{}]. Skipping duplicate AI processing for file: {}",
                    hash, filePath);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn(
                    "Audio processing aborted. Resource file does not exist on disk at path: {}",
                    filePath);
                return;
            }

            String transcript;
            AudioSummary entity;

            if (dbRecord.isPresent()) {
                entity = dbRecord.get();
                transcript = entity.getTranscript();
            } else {
                // Execute ASR (Whisper) transcription
                log.info("Cache/DB miss for hash: [{}]. Triggering ASR (Whisper) execution...",
                    hash);
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

            // Execute LLM Summarization
            log.info("Triggering LLM text summarization for hash: [{}]...", hash);
            String summary = audioAiSummaryService.callChatClientToSummarizeAudioWithRetry(
                transcript);

            if (summary == null || summary.isBlank()) {
                log.warn("LLM audio summarization returned empty output for file: {}", filePath);
                return;
            }

            entity.setSummary(summary);
            cacheService.saveAudioSummary(entity);
            log.info(
                "Audio processing pipeline executed successfully. Record persisted for hash: [{}]",
                hash);

        } catch (Exception e) {
            log.error(
                "Fatal exception or structural IO crash encountered while processing audio resource context: {}",
                filePath, e);
        }
    }

    /**
     * Retrieves audio summary records for a specific chat/session UUID, sanitized to hide full path
     * structures, sorted by audio timestamp extracted from the path, and paginated according to the
     * provided Pageable options.
     *
     * @param uuid     unique target session/chat identifier
     * @param pageable pagination parameters (page number and page size)
     * @return Page of {@link AudioSummary} records scoped to the provided UUID
     */
    public Page<AudioSummary> getAudioSummariesByUuid(String uuid, Pageable pageable) {
        log.info("Requesting audio summary records for session UUID: [{}] (page: {}, size: {})",
            uuid, pageable.getPageNumber(), pageable.getPageSize());

        // 1. Fetch full list (cached or DB)
        List<AudioSummary> entities = cacheService.getAudioSummariesByUuid(uuid);

        // 2. Sanitize file paths & sort by audio path timestamp
        List<AudioSummary> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(this::extractAudioTimestamp))
            .toList();

        // 3. Slice list for pagination
        int total = processedList.size();
        int start = (int) pageable.getOffset();

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int end = Math.min(start + pageable.getPageSize(), total);
        List<AudioSummary> pageContent = processedList.subList(start, end);

        return new PageImpl<>(pageContent, pageable, total);
    }

    /**
     * Isolated helper method dealing strictly with Whisper ASR transcription tasks.
     */
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

    /**
     * Creates a shallow copy of AudioSummary with a relative filePath. e.g., converts
     * "/Users/.../uploads/{uuid}/voice/20260618/file.mp3" to "voice/20260618/file.mp3"
     */
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

    /**
     * Extracts the Unix timestamp integer from file paths.
     */
    private long extractAudioTimestamp(AudioSummary entity) {
        if (entity.getFilePath() == null) {
            return 0L;
        }
        try {
            String fileName = Paths.get(entity.getFilePath()).getFileName().toString();
            String timestampStr = fileName.split("_")[0];
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            log.warn("Failed to parse audio creation timestamp from path: {}. Falling back to 0.",
                entity.getFilePath());
            return 0L;
        }
    }

    /**
     * Deletes a single audio summary record by its ID (hash).
     *
     * @param id target entity ID (audio hash) to delete
     */
    public void deleteAudioSummaryById(String id) {
        cacheService.deleteAudioSummaryById(id);
    }

    /**
     * Deletes multiple audio summary records by their IDs (hashes).
     *
     * @param ids list of target entity IDs (audio hashes) to delete
     */
    public void deleteAudioSummariesByIds(List<String> ids) {
        cacheService.deleteAudioSummariesByIds(ids);
    }

    /**
     * Retrieves the audio file resource and content type by record ID (hash).
     *
     * @param id target entity ID (audio hash)
     * @return Optional containing an AudioFileResource wrapper if found and exists on disk
     */
    public Optional<AudioFileResource> getAudioFileById(String id) {
        log.info("Fetching audio file resource for ID: [{}]", id);
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

    /**
     * Clears only the summary text of a single audio record by its ID (hash).
     *
     * @param id target entity ID (audio hash)
     */
    public void clearAudioSummaryTextById(String id) {
        cacheService.clearAudioSummaryTextById(id);
    }

    /**
     * Clears only the summary text for multiple audio records by their IDs (hashes).
     *
     * @param ids list of target entity IDs (audio hashes)
     */
    public void clearAudioSummaryTextsByIds(List<String> ids) {
        cacheService.clearAudioSummaryTextsByIds(ids);
    }

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
     * Simple record wrapper for audio resource response payload.
     */
    public record AudioFileResource(Resource resource, String contentType) {

    }
}