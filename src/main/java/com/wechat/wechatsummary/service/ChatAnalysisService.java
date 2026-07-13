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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisService {

    private static final int MAX_CHUNK_CHARS = 15000;
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("<summary>(.*?)</summary>",
        Pattern.DOTALL);

    private final AiService aiService;
    private final StorageConfig storageConfig;
    private final ChatAnalysisTaskRepository taskRepository;
    private final WeChatSummaryCacheService cacheService;

    @Async
    public void analyzeChatLogAsync(UUID uuid) {
        log.info("Starting/Resuming asynchronous analysis execution thread for task UUID: {}",
            uuid);

        // Update state to RUNNING in Redis/DB
        updateTaskStatus(uuid, "RUNNING", null);

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
                    .orElseThrow(() -> new RuntimeException(
                        "Target cleaned file could not be found for prefix: " + filePrefix));
            }

            String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);
            List<String> chunks = splitContent(rawContent);
            if (chunks.isEmpty()) {
                log.warn("Aborting task process. Content empty for UUID: {}", uuid);
                taskRepository.updateStatusToFailed(uuid, "清洗后的聊天记录文件内容为空");
                updateTaskStatus(uuid, "FAILED", "清洗后的聊天记录文件内容为空");
                return;
            }

            int startIndex = 0;
            String previousContextSummary = "【前情提要：这是聊天的开端】";

            // 1. Checkpoint Recovery (Disk first for safety/fallback, but index tracking moves to Redis state if active)
            if (Files.exists(tempProgressPath)) {
                try {
                    List<String> lines = Files.readAllLines(tempProgressPath,
                        StandardCharsets.UTF_8);
                    if (!lines.isEmpty()) {
                        startIndex = Integer.parseInt(lines.get(0).trim()) + 1;
                        previousContextSummary = String.join("\n", lines.subList(1, lines.size()));
                        log.info("Checkpoint detected for UUID: {}. Resuming from chunk: {}", uuid,
                            startIndex);
                    }
                } catch (Exception e) {
                    log.warn(
                        "Failed to read progress trace checkpoint file for UUID: {}. Slicing from scratch.",
                        uuid, e);
                }
            }

            log.info("Task UUID: {} processing loop. Total chunks: {}, Target starting index: {}",
                uuid, chunks.size(), startIndex);

            boolean pausedMidway = false;

            for (int i = startIndex; i < chunks.size(); i++) {
                // 2. PAUSE CHECK: Query Redis state before analyzing next block
                String currentStatus = cacheService.getTaskStatus(
                    uuid); // Access internal Redis key/status mapping
                if ("PAUSED".equalsIgnoreCase(currentStatus)) {
                    log.info("Task UUID: {} received a PAUSE command. Halting progression safely.",
                        uuid);
                    pausedMidway = true;
                    break;
                }

                // 3. Update Redis Progress Real-time Metatags
                cacheService.updateTaskProgress(uuid, i, chunks.size());

                log.info("Processing segment ({}/{}) for task UUID: {}", i + 1, chunks.size(),
                    uuid);
                String chunk = chunks.get(i);

                String rawModelOutput = aiService.callChatClientToSummarizeTextWithRetry(
                    previousContextSummary, chunk);
                Matcher matcher = SUMMARY_PATTERN.matcher(rawModelOutput);
                String cleanSummary;
                if (matcher.find()) {
                    cleanSummary = matcher.group(1).trim();
                } else {
                    cleanSummary = rawModelOutput.replaceAll("<.*?>", "").trim();
                    if (cleanSummary.contains("Wait 600")) {
                        cleanSummary = cleanSummary.split("(?i)wait\\s+\\d+")[0].trim();
                    }
                }

                previousContextSummary = cleanSummary;

                // Retain local safety file state backup alongside Redis
                String tempFileContent = i + "\n" + previousContextSummary;
                Files.writeString(tempProgressPath, tempFileContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            // If we broke early because of a pause command, don't execute finalizing blocks
            if (pausedMidway) {
                taskRepository.updateStatusToFailed(uuid,
                    "PAUSED"); // Or a customized updateStatusToPaused
                updateTaskStatus(uuid, "PAUSED", null);
                return;
            }

            // Final Storage Synchronization Phase
            String finalReport = previousContextSummary;
            Path resultTxtPath = outputsDir.resolve(uuid + "_summary.txt");
            String absolutePathStr = resultTxtPath.toAbsolutePath().toString();

            Files.writeString(resultTxtPath, finalReport, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(tempProgressPath);

            taskRepository.updateResult(uuid, absolutePathStr);
            taskRepository.updateStatusToSuccess(uuid);
            updateTaskStatus(uuid, "SUCCESS", absolutePathStr);
            cacheService.clearProgress(
                uuid); // Purge temporal progress metrics from Redis cache counters

        } catch (Exception e) {
            log.error("Unhandled pipeline trace interruption for task UUID: {}", uuid, e);
            String errorMsg = "分析在后台中断（已保留断点）：" + e.getMessage();
            taskRepository.updateStatusToFailed(uuid, errorMsg);
            updateTaskStatus(uuid, "FAILED", errorMsg);
        }
    }

    /**
     * Pauses an active analysis task.
     */
    public void pauseAnalysis(UUID uuid) {
        log.info("Request received to pause task UUID: {}", uuid);
        // Instructing the worker thread loop to exit on next cycle via Redis flag
        cacheService.setTaskStatus(uuid, "PAUSED");
    }

    /**
     * Restarts a task from scratch, purging all current temporary files and states.
     */
    public void startOverAnalysis(UUID uuid) {
        log.info("Request received to START OVER task UUID: {}", uuid);

        // 1. Remove physical cached temp files
        Path outputsDir = storageConfig.getUploadDir().resolve("outputs");
        Path tempProgressPath = outputsDir.resolve(uuid + "_analysis.temp");
        try {
            Files.deleteIfExists(tempProgressPath);
        } catch (Exception e) {
            log.warn("Could not delete temp tracking progress file for restart of UUID: {}", uuid,
                e);
        }

        // 2. Wipe progress counters in Redis
        cacheService.clearProgress(uuid);
    }

    public Map<String, Object> getProgressAndStatus(UUID uuid) {
        Map<String, Object> response = new java.util.HashMap<>();

        // 1. Fetch current lifecycle task meta from DB/Cache
        Optional<ChatAnalysisTask> taskOpt = cacheService.getCachedTask(uuid);
        if (taskOpt.isEmpty()) {
            response.put("status", "NOT_FOUND");
            response.put("progress", 0);
            return response;
        }

        ChatAnalysisTask task = taskOpt.get();
        String status = task.getStatus();
        response.put("taskId", uuid);
        response.put("status", status);

        // 2. Attach data or calculate progress percentages based on status
        switch (status) {
            case "SUCCESS":
                response.put("progress", 100);
                try {
                    Path txtPath = java.nio.file.Paths.get(task.getResult());
                    if (Files.exists(txtPath)) {
                        String content = Files.readString(txtPath, StandardCharsets.UTF_8);
                        response.put("result", content); // Send final data directly to FE
                    } else {
                        response.put("status", "FAILED");
                        response.put("errorMessage", "分析已完成，但报告文件丢失。");
                    }
                } catch (Exception e) {
                    response.put("status", "FAILED");
                    response.put("errorMessage", "读取分析报告文件失败: " + e.getMessage());
                }
                break;

            case "FAILED":
                response.put("progress", 0);
                response.put("errorMessage",
                    task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误");
                break;

            case "PAUSED":
                // Retain progress percentage snapshot even if paused
                Map<String, Integer> pausedMetrics = cacheService.getProgressMetrics(uuid);
                double pausedPercent = calculatePercentage(pausedMetrics);
                response.put("progress", pausedPercent);
                break;

            default: // PROCESSING / RUNNING
                Map<String, Integer> metrics = cacheService.getProgressMetrics(uuid);
                double currentPercent = calculatePercentage(metrics);
                response.put("progress", currentPercent);
                break;
        }

        return response;
    }

    private double calculatePercentage(Map<String, Integer> metrics) {
        if (metrics != null && metrics.containsKey("totalChunks")
            && metrics.get("totalChunks") > 0) {
            int current = metrics.get("processedIndex") + 1;
            int total = metrics.get("totalChunks");
            return Math.min(100.0, ((double) current / total) * 100);
        }
        return 0.0;
    }

    private void updateTaskStatus(UUID uuid, String status, String meta) {
        cacheService.saveAndCacheTask(ChatAnalysisTask.builder()
            .id(uuid)
            .status(status)
            .errorMessage(status.equals("FAILED") ? meta : null)
            .result(status.equals("SUCCESS") ? meta : null)
            .build());
    }

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