package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.dto.ChatPreviewResponse;
import com.wechat.wechatsummary.dto.ChatPreviewRow;
import com.wechat.wechatsummary.dto.SummaryProgressResponse;
import com.wechat.wechatsummary.entity.ChatSummaryStatus;
import com.wechat.wechatsummary.entity.ChatSummaryTask;
import com.wechat.wechatsummary.exception.ResourceNotFoundException;
import com.wechat.wechatsummary.repository.ChatSummaryTaskRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSummaryService {

    private static final int MAX_CHUNK_CHARS = 15000;
    private final AiService aiService;
    private final StoragePaths storagePaths;
    private final ChatSummaryTaskRepository taskRepository;

    private final Map<UUID, Thread> activeThreads = new ConcurrentHashMap<>();

    @Async
    public void summarizeChatLogAsync(String userId, UUID uuid, LocalDateTime startTime,
        LocalDateTime endTime) {
        if (activeThreads.containsKey(uuid)) {
            log.warn(
                "Execution request rejected for user UUID: [{}] and task {}: Thread is already running.",
                userId, uuid);
            return;
        }

        log.info(
            "Starting worker thread for user UUID: [{}] and task UUID: [{}] starting time: [{}], end time: [{}]",
            userId, uuid, startTime, endTime);
        activeThreads.put(uuid, Thread.currentThread());

        Path userOutputsDir = storagePaths.outputDir(userId);
        Path tempProgressPath = storagePaths.summaryTemp(userId, uuid.toString());
        Path resultTxtPath = storagePaths.summaryTxt(userId, uuid.toString());

        try {
            Files.createDirectories(userOutputsDir);
            Path targetFilePath = locateProcessedFile(userOutputsDir, uuid);
            String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);

            // If user specified a start or end timestamp window, filter the content
            if (startTime != null || endTime != null) {
                rawContent = filterContentTimeWindow(targetFilePath, startTime, endTime);
            }

            List<String> chunks = splitContent(rawContent);
            if (chunks.isEmpty()) {
                throw new RuntimeException("Cleaned log file is empty after applying filters.");
            }

            int startIndex = 0;
            String previousContextSummary;

            if (Files.exists(tempProgressPath)) {
                List<String> lines = Files.readAllLines(tempProgressPath, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    int savedIndex = Integer.parseInt(lines.get(0).trim());
                    startIndex = savedIndex + 1;
                    previousContextSummary = String.join("\n", lines.subList(1, lines.size()));
                    log.info("Resuming user UUID: [{}] task {} from chunk index: {}", userId, uuid,
                        startIndex);
                } else {
                    previousContextSummary = "【以下是群聊数据的递进总结摘要：】";
                }
            } else {
                previousContextSummary = "【以下是群聊数据的递进总结摘要：】";
                String initialTempContent = "-1\n" + previousContextSummary;
                Files.writeString(tempProgressPath, initialTempContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info(
                    "Initialized pristine workspace progress snapshot file for user UUID: [{}] and task: {}",
                    userId, uuid);
            }

            for (int i = startIndex; i < chunks.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info(
                        "Pipeline thread for user UUID: [{}] detected interrupt signal. Exiting immediately.",
                        userId);
                    return;
                }

                log.info("Processing segment ({}/{}) for user UUID: [{}] and task UUID: {}", i + 1,
                    chunks.size(),
                    userId, uuid);

                String rawModelOutput = aiService.callChatClientToSummarizeTextWithRetry(
                    previousContextSummary, chunks.get(i));
                previousContextSummary = rawModelOutput.trim();

                String tempFileContent = i + "\n" + previousContextSummary;
                Files.writeString(tempProgressPath, tempFileContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            Files.writeString(resultTxtPath, previousContextSummary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(tempProgressPath);
            log.info(
                "Summary task complete. Summary file generated successfully for user UUID: [{}] and trace: {}",
                userId, uuid);

        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                log.info(
                    "Thread for user UUID: [{}] and task {} was safely interrupted and halted.",
                    userId, uuid);
            } else {
                log.error("Fatal processing failure for user UUID: [{}] and task UUID: [{}]",
                    userId, uuid, e);
                taskRepository.logFailureReason(uuid, e.getMessage());
            }
        } finally {
            activeThreads.remove(uuid);
        }
    }

    private String filterContentTimeWindow(Path targetFilePath, LocalDateTime startTime,
        LocalDateTime endTime) throws Exception {
        List<String> lines = Files.readAllLines(targetFilePath, StandardCharsets.UTF_8);
        List<String> filteredLines = new ArrayList<>();

        // Build a flexible formatter that handles optional milliseconds/fractional seconds
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
            .optionalEnd()
            .toFormatter();

        // Regex to extract timestamps whether they have milliseconds or not (e.g. [2026-07-28 13:41:08] or [2026-07-28 13:41:08.123])
        Pattern timestampPattern = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)]");

        boolean keepLine = (startTime == null);

        for (String line : lines) {
            if (line.startsWith("---") || line.startsWith("群名称") || line.startsWith(
                "总消息数")) {
                filteredLines.add(line);
                continue;
            }

            Matcher matcher = timestampPattern.matcher(line);
            if (matcher.find()) {
                LocalDateTime lineTime = LocalDateTime.parse(matcher.group(1), formatter);

                if (!keepLine && startTime != null && !lineTime.isBefore(startTime)) {
                    keepLine = true;
                }

                if (endTime != null && lineTime.isAfter(endTime)) {
                    break;
                }
            }

            if (keepLine) {
                filteredLines.add(line);
            }
        }

        if (filteredLines.isEmpty()) {
            log.warn(
                "No lines matched the criteria (startTime: {}, endTime: {}). Falling back to full content.",
                startTime, endTime);
            return String.join("\n", lines);
        }

        return String.join("\n", filteredLines);
    }

    public ChatPreviewResponse getChatPreviewData(String userId, UUID uuid) {
        Path userOutputsDir = storagePaths.outputDir(userId);
        Map<String, String> metadata = new HashMap<>();
        List<ChatPreviewRow> chatRows = new ArrayList<>();

        try {
            Path targetFilePath = locateProcessedFile(userOutputsDir, uuid);
            List<String> lines = Files.readAllLines(targetFilePath, StandardCharsets.UTF_8);

            int chatLineCounter = 1;
            boolean parsingHeader = false;

            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }

                if (line.contains("--- 群聊基本信息 ---")) {
                    parsingHeader = true;
                    continue;
                }
                if (parsingHeader) {
                    if (line.contains("--------------------")) {
                        parsingHeader = false;
                        continue;
                    }
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        metadata.put(parts[0].trim(), parts[1].trim());
                    }
                    continue;
                }

                String lineId = String.valueOf(chatLineCounter++);
                String timestamp = "";
                String sender = "";
                String content = line;

                if (line.startsWith("[") && line.contains("] ")) {
                    int closeBracketIdx = line.indexOf("] ");
                    timestamp = line.substring(1, closeBracketIdx);
                    String remainder = line.substring(closeBracketIdx + 2);

                    int colonIdx = remainder.indexOf(": ");
                    if (colonIdx != -1) {
                        sender = remainder.substring(0, colonIdx);
                        content = remainder.substring(colonIdx + 2);
                    } else {
                        sender = "Unknown";
                        content = remainder;
                    }
                }

                chatRows.add(new ChatPreviewRow(lineId, timestamp, sender, content));
            }

            return new ChatPreviewResponse(metadata, chatRows);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse preview chat data for user UUID: [{}] and task UUID: [{}]",
                userId, uuid, e);
            throw new ResourceNotFoundException("Failed to load chat preview");
        }
    }

    public void pauseSummary(UUID uuid) {
        Thread worker = activeThreads.remove(uuid);
        if (worker != null) {
            log.info("Aborting running thread instantly for task: {}", uuid);
            worker.interrupt();
        } else {
            log.info("Pause request ignored for task {}: No active thread found.", uuid);
        }
    }

    public void startOverSummary(String userId, UUID uuid) {
        pauseSummary(uuid);

        try {
            Files.deleteIfExists(storagePaths.summaryTxt(userId, uuid.toString()));
            Files.deleteIfExists(storagePaths.summaryTemp(userId, uuid.toString()));
            log.info(
                "Successfully wiped processing states for complete restart on user UUID: [{}] and task: {}",
                userId, uuid);
        } catch (Exception e) {
            log.warn("Failed to wipe files during clean restart initialization", e);
        }
    }

    public SummaryProgressResponse getStatusAndProgress(String userId, UUID uuid) {
        Path resultTxtPath = storagePaths.summaryTxt(userId, uuid.toString());
        Path tempProgressPath = storagePaths.summaryTemp(userId, uuid.toString());

        if (Files.exists(resultTxtPath)) {
            try {
                return new SummaryProgressResponse(uuid, ChatSummaryStatus.SUCCESS, 100.0,
                    Files.readString(resultTxtPath, StandardCharsets.UTF_8), null);
            } catch (Exception e) {
                return new SummaryProgressResponse(uuid, ChatSummaryStatus.FAILED, 0.0, null,
                    "Error reading summary output: " + e.getMessage());
            }
        }

        if (Files.exists(tempProgressPath)) {
            double progress = 0.0;
            try {
                List<String> lines = Files.readAllLines(tempProgressPath, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    int processedIndex = Integer.parseInt(lines.get(0).trim());

Path targetFilePath = locateProcessedFile(storagePaths.outputDir(userId),
                        uuid);
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
            return new SummaryProgressResponse(uuid,
                isRunning ? ChatSummaryStatus.RUNNING : ChatSummaryStatus.PAUSED, progress, null,
                null);
        }

        Optional<ChatSummaryTask> loggedTask = taskRepository.findById(uuid);
        if (loggedTask.isPresent() && loggedTask.get().getStatus() == ChatSummaryStatus.FAILED) {
            String errorMessage = loggedTask.get().getErrorMessage() != null
                ? loggedTask.get().getErrorMessage()
                : "An unexpected backend breakdown occurred.";
            return new SummaryProgressResponse(uuid, ChatSummaryStatus.FAILED, 0.0, null,
                errorMessage);
        }

        return new SummaryProgressResponse(uuid, ChatSummaryStatus.INITIAL_STATE, 0.0, null, null);
    }

    private Path locateProcessedFile(Path userOutputsDir, UUID uuid) throws Exception {
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";
        if (!Files.exists(userOutputsDir)) {
            throw new ResourceNotFoundException(
                "Processed markdown source directory not found for trace: " + filePrefix);
        }
        try (var stream = Files.list(userOutputsDir)) {
            return stream
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(filePrefix) && name.endsWith(fileSuffix);
                })
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
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