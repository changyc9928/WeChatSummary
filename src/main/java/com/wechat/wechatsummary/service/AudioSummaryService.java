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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudioSummaryService {

    private final ChatModel chatModel;
    private final OpenAiAudioTranscriptionModel transcriptionModel;
    private final MediaSummaryCacheService cacheService;
    private final AudioSummaryRepository repository;

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

        log.info("Cache miss. Transcribing Malaysian accent audio...");
        try {
            FileSystemResource audioResource = new FileSystemResource(Paths.get(filePath));

            // 配置大马本地口音提示词
            var transcriptionOptions = OpenAiAudioTranscriptionOptions.builder()
                .prompt(
                    "这是一段马来西亚华人的日常语音对话，里面包含大量大马华语口音（Manglish），可能会掺杂马来语（如 pasar, mamak, makan, pun, tapi）、英语单词以及本地叹词如 lah, leh, meh, siao, walau。请尽可能准确地转录为文字。")
                .responseFormat(AudioResponseFormat.JSON)
                .build();

            AudioTranscriptionPrompt transcriptionPrompt = new AudioTranscriptionPrompt(
                audioResource, transcriptionOptions);
            var transcriptionResponse = transcriptionModel.call(transcriptionPrompt);
            String transcript = transcriptionResponse.getResult().getOutput();

            if (transcript == null || transcript.isBlank()) {
                throw new RuntimeException("Failed to transcribe audio.");
            }

            // 交给 Llama-4 整理摘要
            String llmPrompt = String.format("""
                你是一个精通马来西亚多元文化语言（Rojak 华语/Manglish）的智能助手。
                下面是一段马来西亚本地人的语音转录文本，其中可能混杂了华语、英语、马来语（Malay）和方言。
                
                请帮我理解这段话的核心意思，并用【规范、流畅的中文】写一段精简的摘要，列出核心要点（如有待办事项或重要结论请单独列出）。
                
                转录文本：
                \"\"\"
                %s
                \"\"\"
                """, transcript);

            String summary = chatModel.call(llmPrompt);

            // 存储与缓存
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
            log.error("Error occurred while processing audio summary for file: {}", filePath, e);
            throw new RuntimeException("Audio processing failed: " + e.getMessage(), e);
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