package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.entity.AnalysisStatus;
import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnalysisService {

    private static final int MAX_CHUNK_CHARS = 15000;
    private final AiService aiService;
    private final StorageConfig storageConfig;
    private final ChatAnalysisTaskRepository taskRepository;

    private final Map<UUID, Thread> activeThreads = new ConcurrentHashMap<>();

    @Async
    public void analyzeChatLogAsync(String userId, UUID uuid) {
        // Guardrail against double clicks: reject immediately if a thread is already running
        if (activeThreads.containsKey(uuid)) {
            log.warn("Execution request rejected for user UUID: [{}] and task {}: Thread is already running.", userId, uuid);
            return;
        }

        log.info("Starting worker thread for user UUID: [{}] and task UUID: [{}]", userId, uuid);
        activeThreads.put(uuid, Thread.currentThread());

        Path userOutputsDir = storageConfig.getUploadDir().resolve(userId).resolve("outputs");
        Path tempProgressPath = userOutputsDir.resolve(uuid + "_analysis.temp");
        Path resultTxtPath = userOutputsDir.resolve(uuid + "_summary.txt");

        try {
            Files.createDirectories(userOutputsDir);
            Path targetFilePath = locateProcessedFile(userOutputsDir, uuid);
            String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);
            List<String> chunks = splitContent(rawContent);
            if (chunks.isEmpty()) {
                throw new RuntimeException("Cleaned log file is empty.");
            }

            int startIndex = 0;
            String previousContextSummary;

            // 1. Initialization and Checkpoint Recovery Structure
            if (Files.exists(tempProgressPath)) {
                List<String> lines = Files.readAllLines(tempProgressPath, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    int savedIndex = Integer.parseInt(lines.get(0).trim());
                    startIndex = savedIndex + 1;
                    previousContextSummary = String.join("\n", lines.subList(1, lines.size()));
                    log.info("Resuming user UUID: [{}] task {} from chunk index: {}", userId, uuid, startIndex);
                } else {
                    previousContextSummary = "【以下是群聊数据的递进总结摘要：】";
                }
            } else {
                previousContextSummary = "【以下是群聊数据的递进总结摘要：】";
                String initialTempContent = "-1\n" + previousContextSummary;
                Files.writeString(tempProgressPath, initialTempContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("Initialized pristine workspace progress snapshot file for user UUID: [{}] and task: {}",
                    userId, uuid);
            }

            // 2. Main Chunk Execution Workflow Loop
            for (int i = startIndex; i < chunks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Pipeline thread for user UUID: [{}] detected interrupt signal. Exiting immediately.", userId);
                    return;
                }

                log.info("Processing segment ({}/{}) for user UUID: [{}] and task UUID: {}", i + 1, chunks.size(),
                    userId, uuid);

                String rawModelOutput = aiService.callChatClientToSummarizeTextWithRetry(
                    previousContextSummary, chunks.get(i));
                previousContextSummary = rawModelOutput.trim();

                String tempFileContent = i + "\n" + previousContextSummary;
                Files.writeString(tempProgressPath, tempFileContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            // 3. Close & Clean Up Output Operations
            Files.writeString(resultTxtPath, previousContextSummary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(tempProgressPath);
            log.info("Analysis task complete. Summary file generated successfully for user UUID: [{}] and trace: {}",
                userId, uuid);

        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                log.info("Thread for user UUID: [{}] and task {} was safely interrupted and halted.", userId, uuid);
            } else {
                log.error("Fatal processing failure for user UUID: [{}] and task UUID: [{}]", userId, uuid, e);
                taskRepository.logFailureReason(uuid, e.getMessage());
            }
        } finally {
            activeThreads.remove(uuid);
        }
    }

    public void pauseAnalysis(UUID uuid) {
        Thread worker = activeThreads.remove(uuid);
        if (worker != null) {
            log.info("Aborting running thread instantly for task: {}", uuid);
            worker.interrupt();
        } else {
            log.info("Pause request ignored for task {}: No active thread found.", uuid);
        }
    }

    public void startOverAnalysis(String userId, UUID uuid) {
        // Force break active threads before touching files to block file-lock collisions
        pauseAnalysis(uuid);

        Path userOutputsDir = storageConfig.getUploadDir().resolve(userId).resolve("outputs");
        try {
            Files.deleteIfExists(userOutputsDir.resolve(uuid + "_summary.txt"));
            Files.deleteIfExists(userOutputsDir.resolve(uuid + "_analysis.temp"));
            log.info("Successfully wiped processing states for complete restart on user UUID: [{}] and task: {}", userId, uuid);
        } catch (Exception e) {
            log.warn("Failed to wipe files during clean restart initialization", e);
        }
    }

    public Map<String, Object> getStatusAndProgress(String userId, UUID uuid) {
        Map<String, Object> response = new HashMap<>();
        response.put("taskId", uuid);

        Path userOutputsDir = storageConfig.getUploadDir().resolve(userId).resolve("outputs");
        Path resultTxtPath = userOutputsDir.resolve(uuid + "_summary.txt");
        Path tempProgressPath = userOutputsDir.resolve(uuid + "_analysis.temp");

        // Rule 1: Summary file exists -> SUCCESS
        if (Files.exists(resultTxtPath)) {
            try {
                response.put("status", AnalysisStatus.SUCCESS);
                response.put("progress", 100.0);
                response.put("result", Files.readString(resultTxtPath, StandardCharsets.UTF_8));
                return response;
            } catch (Exception e) {
                response.put("status", AnalysisStatus.FAILED);
                response.put("errorMessage", "Error reading summary output: " + e.getMessage());
                return response;
            }
        }

        // Rule 2: Temp file exists -> RUNNING or PAUSED
        if (Files.exists(tempProgressPath)) {
            double progress = 0.0;
            try {
                List<String> lines = Files.readAllLines(tempProgressPath, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    int processedIndex = Integer.parseInt(lines.get(0).trim());

                    Path targetFilePath = locateProcessedFile(userOutputsDir, uuid);
                    String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);
                    int totalChunks = splitContent(rawContent).size();

                    if (totalChunks > 0) {
                        progress = Math.min(99.9,
                            ((double) (processedIndex + 1) / totalChunks) * 100);
                    }
                }
            } catch (Exception ignored) {
            }

            boolean isRunning = activeThreads.containsKey(uuid);
            response.put("status", isRunning ? AnalysisStatus.RUNNING : AnalysisStatus.PAUSED);
            response.put("progress", progress);
            return response;
        }

        // Rule 3: Check database log context indicators
        Optional<ChatAnalysisTask> loggedTask = taskRepository.findById(uuid);
        if (loggedTask.isPresent() && "FAILED".equals(loggedTask.get().getStatus())) {
            response.put("status", AnalysisStatus.FAILED);
            response.put("progress", 0.0);
            response.put("errorMessage", loggedTask.get().getErrorMessage() != null ?
                loggedTask.get().getErrorMessage() : "An unexpected backend breakdown occurred.");
            return response;
        }

        // Rule 4: Absolutely pristine baseline condition
        response.put("status", AnalysisStatus.INITIAL_STATE);
        response.put("progress", 0.0);
        return response;
    }

    private Path locateProcessedFile(Path userOutputsDir, UUID uuid) throws Exception {
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";
        if (!Files.exists(userOutputsDir)) {
            throw new RuntimeException("Processed markdown source directory not found for trace: " + filePrefix);
        }
        try (var stream = Files.list(userOutputsDir)) {
            return stream
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(filePrefix) && name.endsWith(fileSuffix);
                })
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                    "Processed markdown source file not found for trace: " + filePrefix));
        }
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