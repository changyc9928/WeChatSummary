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
 * Service orchestration class responsible for managing the end-to-end audio processing lifecycle.
 * Coordinates lookup checks between the cache layer, persistence layer, and coordinates remote AI
 * transcription and summarization operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioProcessorService {

    /**
     * Interface layer to external AI operations.
     */
    private final AiService audioAiSummaryService;

    /**
     * In-memory cache for generated summaries.
     */
    private final WeChatSummaryCacheService cacheService;

    /**
     * Persistent storage for transcription and summary records.
     */
    private final AudioSummaryRepository repository;

    /**
     * Processes an audio file by executing a layered lookup strategy:
     * <ol>
     * <li>Checking the local in-memory cache layer.</li>
     * <li>Checking the relational database storage.</li>
     * <li>Transcribing the audio via the Whisper service if there is a miss.</li>
     * <li>Generating a structured summary using the LLM client.</li>
     * <li>Persisting and caching the end results to minimize repeated execution.</li>
     * </ol>
     *
     * @param filePath system path pointing to the audio source file
     * @return summarized content produced by the LLM
     * @throws IllegalArgumentException if the provided audio file path cannot be resolved locally
     * @throws RuntimeException         if transcription strings return blank or downstream AI calls
     *                                  fail
     */
    public String processAudioSummary(String filePath) {
        String pathHash = sha256(filePath);
        log.info("Initiating audio summary process for file path: [{}] with hash: [{}]", filePath,
            pathHash);

        // Layer 1: Cache Lookup
        Optional<String> cachedSummary = cacheService.getAudioSummary(pathHash);
        if (cachedSummary.isPresent()) {
            log.info("Cache hit for file hash: [{}]. Returning cached summary.", pathHash);
            return cachedSummary.get();
        }

        // Layer 2: Database Lookup
        Optional<AudioSummary> dbSummary = repository.findByFileHash(pathHash);
        if (dbSummary.isPresent()) {
            log.info(
                "Database hit for file hash: [{}]. Backfilling cache layer and returning summary.",
                pathHash);
            String summary = dbSummary.get().getSummary();
            cacheService.putAudioSummary(pathHash, summary);
            return summary;
        }

        // Layer 3: Cache/DB Miss -> Triggering Remote AI Services
        log.info(
            "Cache and database miss for hash: [{}]. Proceeding with remote Whisper audio transcription...",
            pathHash);

        try {
            FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));
            if (!audioResource.exists()) {
                log.warn(
                    "Audio processing aborted. Resource file does not exist on disk at path: {}",
                    filePath);
                throw new IllegalArgumentException("Audio file does not exist: " + filePath);
            }

            String localAIPrompt = "这是一段马来西亚华人的日常Rojak华语语音对话。";

            var transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
                .model("large-v3")
                .prompt(localAIPrompt)
                .responseFormat(AudioResponseFormat.JSON)
                .build();

            AudioTranscriptionPrompt transcriptionPrompt = new AudioTranscriptionPrompt(
                audioResource, transcriptionOptions);

            // Invoke through the Spring proxy so @Retryable is applied.
            var transcriptionResponse = audioAiSummaryService.callTranscriptionWithRetry(
                transcriptionPrompt);
            String transcript = transcriptionResponse.getResult().getOutput();

            if (transcript == null || transcript.isBlank()) {
                log.error("Whisper service returned a null or empty transcript output for hash: {}",
                    pathHash);
                throw new RuntimeException("Failed to transcribe audio: transcript is empty.");
            }

            if (log.isDebugEnabled()) {
                log.debug(
                    "Transcription successful for hash: [{}]. Original transcript payload: [{}]",
                    pathHash, transcript);
            } else {
                log.info(
                    "Transcription successful for hash: [{}]. Handing over to LLM for final text summarization.",
                    pathHash);
            }

            // Invoke through the Spring proxy so @Retryable is applied.
            String summary = audioAiSummaryService.callChatClientToSummarizeAudioWithRetry(
                transcript);

            // Layer 4: Persistence and Caching Strategy
            AudioSummary audioSummary = new AudioSummary();
            audioSummary.setId(pathHash);
            audioSummary.setFileHash(pathHash);
            audioSummary.setFilePath(filePath);
            audioSummary.setTranscript(transcript);
            audioSummary.setSummary(summary);

            repository.save(audioSummary);
            cacheService.putAudioSummary(pathHash, summary);

            log.info(
                "Audio pipeline successfully completed. Record saved and cached for hash: [{}]",
                pathHash);
            return summary;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                "Fatal exception pipeline error processing audio summary for file tracking context: {}",
                filePath, e);
            throw new RuntimeException("Audio processing failed: " + e.getMessage(), e);
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