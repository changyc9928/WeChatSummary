package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import com.wechat.wechatsummary.service.ChatAnalysisService;
import com.wechat.wechatsummary.service.WeChatSummaryCacheService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to trigger asynchronous chat log summaries and securely poll
 * real-time processing statuses protected by layered caching filters.
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatAnalysisController {

    private final ChatAnalysisService chatAnalysisService;
    private final ChatAnalysisTaskRepository chatAnalysisTaskRepository;
    private final WeChatSummaryCacheService chatAnalysisCacheService;

    /**
     * Initializes a background chat analysis task execution pipeline. Sets up an initial tracking
     * status in the database/cache layers before spinning up an async worker thread.
     *
     * @param uuid tracking token bound to the chat session files to process
     * @return HTTP 200 response mapping containing the processing task tracking context metadata
     */
    @PostMapping("/{uuid}")
    public ResponseEntity<?> startAnalysis(@PathVariable UUID uuid) {
        log.info(
            "REST request received to initialize chat analysis pipeline loop for task UUID: [{}]",
            uuid);

        chatAnalysisCacheService.saveAndEvictTask(ChatAnalysisTask.builder()
            .id(uuid)
            .status("PROCESSING")
            .build());

        chatAnalysisService.analyzeChatLogAsync(uuid);

        return ResponseEntity.ok(Map.of(
            "status", "PROCESSING",
            "taskId", uuid
        ));
    }

    /**
     * Polls the current state of a running analysis task. Leverages the Spring Cache footprint to
     * eliminate repetitive, expensive I/O disk file operations once a task hits a terminal SUCCESS
     * state.
     *
     * @param uuid tracking token bound to the chat session files to process
     * @return HTTP 200 state payloads or error responses corresponding to file missing states or
     * task tracking errors
     */
    @GetMapping("/status/{uuid}")
    public ResponseEntity<?> getAnalysisStatus(@PathVariable UUID uuid) {
        if (log.isDebugEnabled()) {
            log.debug("Polling execution status request received for tracking token UUID: [{}]",
                uuid);
        }

        return chatAnalysisCacheService.getCachedTask(uuid)
            .map(task -> switch (task.getStatus()) {
                case "SUCCESS" -> {
                    try {
                        Path txtPath = Paths.get(task.getResult());
                        if (!Files.exists(txtPath)) {
                            log.error(
                                "Storage consistency anomaly detected. Task is flagged as SUCCESS in database metadata, but output report file is missing on system path: {}",
                                txtPath);
                            yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("status", "FAILED", "errorMessage",
                                    "分析已完成，但报告文件丢失。"));
                        }

                        String txtContent = Files.readString(txtPath, StandardCharsets.UTF_8);

                        if (log.isDebugEnabled()) {
                            log.debug(
                                "Successfully loaded completed summary text report from absolute disk storage layout path: {}",
                                txtPath);
                        }

                        yield ResponseEntity.ok(Map.of(
                            "status", "SUCCESS",
                            "result", txtContent
                        ));
                    } catch (Exception e) {
                        log.error(
                            "Failed to read system storage report file structure on local path coordinates for task UUID: {}",
                            uuid, e);
                        yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(
                                Map.of("status", "FAILED", "errorMessage", "读取分析报告文件失败"));
                    }
                }
                case "FAILED" -> {
                    log.warn(
                        "Polled task UUID: [{}] returned terminal failure state payload wrapper.",
                        uuid);
                    yield ResponseEntity.ok(Map.of(
                        "status", "FAILED",
                        "errorMessage",
                        task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误"
                    ));
                }
                default -> ResponseEntity.ok(Map.of(
                    "status", "PROCESSING",
                    "message", "大模型正在努力吃瓜分析中，请稍后..."
                ));
            })
            .orElseGet(() -> {
                log.warn(
                    "Polled query rejected. No active tracking metadata could be found for session UUID: [{}]",
                    uuid);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "未找到对应的分析任务。"));
            });
    }
}