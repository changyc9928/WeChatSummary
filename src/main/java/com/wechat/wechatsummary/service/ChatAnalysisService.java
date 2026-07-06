package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service handling asynchronous rolling chat logs analysis with built-in checkpoint mechanisms
 * allowing resumable processing if interruptions occur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisService {

    /**
     * Maximum character count ceiling allowed per chunk block to prevent LLM context overflows.
     */
    private static final int MAX_CHUNK_CHARS = 15000;

    /**
     * Pattern used to extract the summarized content enclosed within summary tags.
     */
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
        "<summary>(.*?)</summary>",
        Pattern.DOTALL
    );

    private final AiService aiService;
    private final StorageConfig storageConfig;
    private final ChatAnalysisTaskRepository taskRepository;
    private final WeChatSummaryCacheService cacheService;

    /**
     * Asynchronously executes a rolling analysis on a pre-processed chat log. Evaluates checkpoints
     * to skip already summarized historical text chunks on execution resume.
     *
     * @param uuid tracking identifier associated with the specific chat analysis task pipeline
     */
    @Async
    public void analyzeChatLogAsync(UUID uuid) {
        log.info(
            "Starting asynchronous resumable rolling analysis execution thread for task UUID: {}",
            uuid);

        Path outputsDir = storageConfig.getUploadDir().resolve("outputs");
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";
        Path tempProgressPath = outputsDir.resolve(uuid + "_analysis.temp");

        try {
            Path targetFilePath;
            try (var stream = Files.list(outputsDir)) {
                targetFilePath = stream
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(filePrefix) && fileName.endsWith(fileSuffix);
                    })
                    .findFirst()
                    .orElseThrow(
                        () -> new RuntimeException(
                            "Target cleaned file could not be found for prefix: " + filePrefix));
            }

            String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);
            List<String> chunks = splitContent(rawContent);
            if (chunks.isEmpty()) {
                log.warn(
                    "Aborting task process. Resolved text content is completely empty for UUID: {}",
                    uuid);

                // 1. Persist failure configuration to physical database
                taskRepository.updateStatusToFailed(uuid, "清洗后的聊天记录文件内容为空");
                // 2. Synchronize memory state mapping inside Redis Cache
                cacheService.saveAndEvictTask(ChatAnalysisTask.builder()
                    .id(uuid)
                    .status("FAILED")
                    .errorMessage("清洗后的聊天记录文件内容为空")
                    .build());
                return;
            }

            int startIndex = 0;
            String previousContextSummary = "【前情提要：这是聊天的开端】";

            // Checkpoint Recovery Logic
            if (Files.exists(tempProgressPath)) {
                try {
                    List<String> lines = Files.readAllLines(tempProgressPath,
                        StandardCharsets.UTF_8);
                    if (!lines.isEmpty()) {
                        startIndex = Integer.parseInt(lines.get(0).trim()) + 1;
                        previousContextSummary = String.join("\n", lines.subList(1, lines.size()));
                        log.info(
                            "Checkpoint detected for UUID: {}. Last successful chunk index: {}. Resuming from chunk: {}",
                            uuid, startIndex - 1, startIndex + 1);
                    }
                } catch (Exception e) {
                    log.warn(
                        "Failed to read progress trace checkpoint file for UUID: {}. Defaulting back to initialization index.",
                        uuid, e);
                    startIndex = 0;
                    previousContextSummary = "流水账开端";
                }
            }

            if (startIndex >= chunks.size()) {
                log.info(
                    "Task UUID: {} tracking indicates all text chunk segments have been processed already. Skipping straight to aggregation finalization.",
                    uuid);
            } else {
                log.info(
                    "Task UUID: {} processing initialized loop cycle. Total chunks: {}, Target baseline starting index: {}",
                    uuid, chunks.size(), startIndex + 1);

                for (int i = startIndex; i < chunks.size(); i++) {
                    log.info(
                        "Processing rolling context segment iteration ({}/{}) for task UUID: {}",
                        i + 1, chunks.size(), uuid);
                    String chunk = chunks.get(i);

                    // Execute LLM execution using proxy wrapped retry context definitions
                    String rawModelOutput = aiService.callChatClientToSummarizeTextWithRetry(
                        previousContextSummary, chunk);

                    // Regex extract information block bound inside tags
                    Matcher matcher = SUMMARY_PATTERN.matcher(rawModelOutput);
                    String cleanSummary;
                    if (matcher.find()) {
                        cleanSummary = matcher.group(1).trim();
                    } else {
                        log.warn(
                            "Task UUID: {} failed to parse <summary> envelope tags on chunk index {}. Executing fallback stripping mechanism.",
                            uuid, i + 1);
                        cleanSummary = rawModelOutput.replaceAll("<.*?>", "").trim();
                        if (cleanSummary.contains("Wait 600")) {
                            cleanSummary = cleanSummary.split("(?i)wait\\s+\\d+")[0].trim();
                        }
                    }

                    previousContextSummary = cleanSummary;

                    if (log.isDebugEnabled()) {
                        log.debug(
                            "Task UUID: {} finalized iteration index {}. Clean summary metadata string length size: {}",
                            uuid, i + 1, previousContextSummary.length());
                    }

                    // Save checkpoint step data elements immediately to safe state files
                    String tempFileContent = i + "\n" + previousContextSummary;
                    Files.writeString(tempProgressPath, tempFileContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

            // Final Storage Synchronization Phase
            String finalReport = previousContextSummary;
            Path resultTxtPath = outputsDir.resolve(uuid + "_summary.txt");
            String absolutePathStr = resultTxtPath.toAbsolutePath().toString();

            // 1. Output final compiled document representation to text files
            Files.writeString(resultTxtPath, finalReport, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 2. Safely discard temporary resume state tracker traces from disk
            Files.deleteIfExists(tempProgressPath);
            log.info(
                "Analysis task pipeline executed flawlessly for UUID: {}. Output committed to: {}. Checkpoint metadata cleaned up.",
                uuid, absolutePathStr);

            // 3. Persist transaction details straight to database layers
            taskRepository.updateResult(uuid, absolutePathStr);
            taskRepository.updateStatusToSuccess(uuid);

            // 4. Update memory mapping states within Redis cluster configurations
            cacheService.saveAndEvictTask(ChatAnalysisTask.builder()
                .id(uuid)
                .status("SUCCESS")
                .result(absolutePathStr)
                .build());

        } catch (Exception e) {
            log.error(
                "Unhandled pipeline trace interruption occurred for task UUID: {}. Execution stopped, historical trace states preserved in checkpoint file.",
                uuid, e);

            String errorMsg = "分析在后台中断（已保留断点）：" + e.getMessage();

            // 1. Write failed indicators straight to persistence mappings
            taskRepository.updateStatusToFailed(uuid, errorMsg);

            // 2. Synchronize Redis cluster state data mappings to prevent clients from hanging infinitely
            cacheService.saveAndEvictTask(ChatAnalysisTask.builder()
                .id(uuid)
                .status("FAILED")
                .errorMessage(errorMsg)
                .build());
        }
    }

    /**
     * Splits the processed chat log into chunks suitable for LLM processing. Tries to respect
     * newline boundaries to prevent mid-sentence segment slicing.
     *
     * @param content the complete string payload containing the raw chat history log
     * @return a list of bounded string chunks split sequentially
     */
    private List<String> splitContent(String content) {
        List<String> chunks = new ArrayList<>();
        int length = content.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + MAX_CHUNK_CHARS, length);
            if (end < length) {
                int nextNewLine = content.lastIndexOf('\n', end);
                if (nextNewLine > start) {
                    end = nextNewLine;
                }
            }
            chunks.add(content.substring(start, end).trim());
            start = end + 1;
        }
        return chunks;
    }
}