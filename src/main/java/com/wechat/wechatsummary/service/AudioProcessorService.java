package com.wechat.wechatsummary.service;

import com.openai.models.audio.AudioResponseFormat;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/**
 * Service orchestration class managing decoupled ASR and LLM lifecycles based on strict record states.
 * This implementation relies directly on DB lookups and triggers cache invalidation upon successful state mutations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioProcessorService {

    private final AiService audioAiSummaryService;
    private final WeChatSummaryCacheService cacheService;
    private final AudioSummaryRepository repository;

    /**
     * Processes audio summary with write-through cache invalidation:
     * <ol>
     * <li>If DB record is missing: Runs ASR, saves the transcript, evicts the cache, and exits.</li>
     * <li>If DB record exists and transcript is present but summary is missing: Runs LLM Summary, updates DB, evicts the cache, and returns.</li>
     * <li>If DB record is fully complete, returns the existing summary text immediately.</li>
     * </ol>
     *
     * @param filePath system path pointing to the audio source file
     * @return summarized content produced by the LLM or blank if processing is incomplete
     */
    public String processAudioSummary(String filePath) {
        String pathHash = sha256(filePath);
        log.info("Initiating audio summary process for file path: [{}] with hash: [{}]", filePath, pathHash);

        // Database Lookup Only (Cache lookups removed)
        Optional<AudioSummary> dbRecord = repository.findByFileHash(pathHash);

        // CASE 1: No DB record found -> Run ASR ONLY, Save, and Evict Cache
        if (dbRecord.isEmpty()) {
            log.info("Database miss for hash: [{}]. Triggering ASR (Whisper) to create initial record...", pathHash);

            String transcript = executeTranscription(filePath);

            AudioSummary newAudioSummary = new AudioSummary();
            newAudioSummary.setId(pathHash);
            newAudioSummary.setFileHash(pathHash);
            newAudioSummary.setFilePath(filePath);
            newAudioSummary.setTranscript(transcript);

            repository.save(newAudioSummary);

            // Invalidate cache following successful ASR record insert
            cacheService.evictAudioSummary(pathHash);
            log.info("ASR complete. Initial transcript saved and cache evicted for hash: [{}]. Exiting phase.", pathHash);

            return "";
        }

        // CASE 2: DB Record exists -> Evaluate LLM Summary condition
        AudioSummary existingRecord = dbRecord.get();
        String transcript = existingRecord.getTranscript();

        if (transcript == null || transcript.isBlank()) {
            log.warn("Database record exists for hash: [{}] but transcript is empty. Rules prevent ASR execution on existing records.", pathHash);
            return "";
        }

        // Check if summary is missing
        if (existingRecord.getSummary() == null || existingRecord.getSummary().isBlank()) {
            log.info("Database record found with transcript for hash: [{}]. Triggering LLM text summarization...", pathHash);

            String summary = audioAiSummaryService.callChatClientToSummarizeAudioWithRetry(transcript);
            existingRecord.setSummary(summary);

            repository.save(existingRecord);

            // Invalidate cache following successful Summary updates
            cacheService.evictAudioSummary(pathHash);
            log.info("LLM summarization completed, database updated, and cache evicted for hash: [{}]", pathHash);

            return summary;
        }

        // Case 3: Both transcript and summary already exist completely in DB
        log.info("Database hit for complete transcript and summary record for hash: [{}]. Returning data.", pathHash);
        return existingRecord.getSummary();
    }

    /**
     * Isolated helper method dealing strictly with Whisper ASR transcription tasks.
     */
    private String executeTranscription(String filePath) {
        FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));
        if (!audioResource.exists()) {
            log.warn("Audio processing aborted. Resource file does not exist on disk at path: {}", filePath);
            throw new IllegalArgumentException("Audio file does not exist: " + filePath);
        }

        String localAIPrompt = "这是一段马来西亚华人的日常Rojak华语语音对话。";

        var transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
            .model("large-v3")
            .prompt(localAIPrompt)
            .responseFormat(AudioResponseFormat.JSON)
            .build();

        AudioTranscriptionPrompt transcriptionPrompt = new AudioTranscriptionPrompt(audioResource, transcriptionOptions);
        var transcriptionResponse = audioAiSummaryService.callTranscriptionWithRetry(transcriptionPrompt);
        String transcript = transcriptionResponse.getResult().getOutput();

        if (transcript == null || transcript.isBlank()) {
            throw new RuntimeException("Failed to transcribe audio: transcript is empty.");
        }
        return transcript;
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
}