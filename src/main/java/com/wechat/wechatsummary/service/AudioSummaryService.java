package com.wechat.wechatsummary.service;

import com.openai.errors.RateLimitException;
import com.openai.models.audio.AudioResponseFormat;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AudioSummaryService {

    private final ChatClient chatClient; // 👈 替换为了 Spring AI 的 ChatClient
    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final MediaSummaryCacheService cacheService;
    private final AudioSummaryRepository repository;
    private final AudioSummaryService self;

    public AudioSummaryService(
        @Qualifier("chatClientBuilder") ChatClient.Builder chatClientBuilder,
        // 👈 推荐通过注入 Builder 来构建
        OpenAiAudioTranscriptionModel transcriptionModel,
        MediaSummaryCacheService cacheService,
        AudioSummaryRepository repository,
        @Lazy AudioSummaryService self) {
        this.chatClient = chatClientBuilder.build();
        this.transcriptionModel = transcriptionModel;
        this.cacheService = cacheService;
        this.repository = repository;
        this.self = self;
    }

    public String processAudioSummary(String filePath) {
        String pathHash = sha256(filePath);
        log.info("Processing audio for path: {}, hash: {}", filePath, pathHash);

        Optional<String> cachedSummary = cacheService.getAudioSummary(pathHash);
        if (cachedSummary.isPresent()) {
            return cachedSummary.get();
        }

        Optional<AudioSummary> dbSummary = repository.findByFileHash(pathHash);
        if (dbSummary.isPresent()) {
            String summary = dbSummary.get().getSummary();
            cacheService.putAudioSummary(pathHash, summary);
            return summary;
        }

        log.info("Cache miss. Transcribing Malaysian accent audio using whisper-large-v3...");
        try {
            FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));
            if (!audioResource.exists()) {
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

            var transcriptionResponse = self.callTranscriptionWithRetry(transcriptionPrompt);
            String transcript = transcriptionResponse.getResult().getOutput();

            if (transcript == null || transcript.isBlank()) {
                throw new RuntimeException("Failed to transcribe audio: transcript is empty.");
            }

            log.info("Transcription success. Handing over to LLM for summary... Original text: {}",
                transcript);

            // 👈 更改为调用基于 ChatClient 的重试方法
            String summary = self.callChatClientWithRetry(transcript);

            AudioSummary audioSummary = new AudioSummary();
            audioSummary.setId(pathHash);
            audioSummary.setFileHash(pathHash);
            audioSummary.setFilePath(filePath);
            audioSummary.setTranscript(transcript);
            audioSummary.setSummary(summary);
            repository.save(audioSummary);

            cacheService.putAudioSummary(pathHash, summary);

            return summary;

        } catch (Exception e) {
            log.error("Final failure occurred while processing audio summary for file: {}",
                filePath, e);
            throw new RuntimeException("Audio processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * ✨ 针对 ChatClient 总结方法进行独立重试 (Spring AI Fluent API Approach)
     */
    @Retryable(
        retryFor = {RateLimitException.class,
            org.springframework.web.client.HttpServerErrorException.class},
        maxAttempts = 10,
        backoff = @Backoff(delay = 30000, maxDelay = 3600000, multiplier = 2.0, random = true)
    )
    public String callChatClientWithRetry(String transcript) {
        log.info("Calling Spring AI ChatClient for Audio Summary...");

        String systemPrompt = """
            你是一个精通马来西亚多元文化语言（Rojak 华语/Manglish）的智能助手。
            下面是一段马来西亚本地人的语音转录文本，其中可能混杂了华语、英语、马来语（Malay）和方言。
            
            请帮我理解这段话的核心意思，并用【规范、流畅的中文】写一段精简的摘要，列出核心要点（如有待办事项或重要结论请单独列出）。
            
            ⚠️注意：输出文本长度严格不能超过原文长度。若原文仅有一句话，请用同样简短的一句话进行提炼，严禁展开无根据的联想和脑补背景。
            """;

        String userPrompt = """
            转录文本：
            \"\"\"
            {transcript}
            \"\"\"
            """;

        try {
            // 使用 Spring AI 链式 API 调用，去除手写 JSON 和 Map 映射
            return chatClient.prompt()
                .system(systemPrompt)
                .user(user -> user.text(userPrompt).param("transcript", transcript))
                .call()
                .content();

        } catch (Exception e) {
            log.error("Failed to execute AI request via ChatClient", e);
            throw new RuntimeException("LLM request failed via ChatClient: " + e.getMessage(), e);
        }
    }

    /**
     * ✨ 针对 Whisper 语音转文字方法进行独立重试
     */
    @Retryable(
        retryFor = {RateLimitException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 30000, maxDelay = 3600000, multiplier = 2.0, random = true)
    )
    public AudioTranscriptionResponse callTranscriptionWithRetry(AudioTranscriptionPrompt prompt) {
        return transcriptionModel.call(prompt);
    }

    /**
     * ✨ 降级兜底
     */
    @Recover
    public String recoverRestClient(Throwable e, String transcript) {
        log.error("Spring AI ChatClient failed completely after all retries. Reason: {}",
            e.getMessage());
        return "【系统提示：由于 AI 服务或网络连接当前请求过于频繁或超时，摘要生成失败。原转录文本如下：】\n"
            + transcript;
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