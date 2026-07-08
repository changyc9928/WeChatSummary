package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.service.ChatAnalysisService;
import com.wechat.wechatsummary.service.WeChatSummaryCacheService;
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
 * REST controller exposing endpoints to trigger asynchronous chat log summaries, track progress via
 * Redis, and control execution states (pause/restart).
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatAnalysisController {

    private final ChatAnalysisService chatAnalysisService;
    private final WeChatSummaryCacheService chatAnalysisCacheService;

    /**
     * Initializes a background chat analysis task execution pipeline.
     */
    @PostMapping("/{uuid}")
    public ResponseEntity<?> startAnalysis(@PathVariable UUID uuid) {
        log.info("REST request received to initialize chat analysis pipeline for task UUID: [{}]",
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
     * Combined polling endpoint. Returns the real-time status, progress percentage, and the final
     * content when completed.
     */
    @GetMapping("/status-pool/{uuid}")
    public ResponseEntity<Map<String, Object>> getStatusAndProgress(@PathVariable UUID uuid) {
        Map<String, Object> report = chatAnalysisService.getProgressAndStatus(uuid);
        if ("NOT_FOUND".equals(report.get("status"))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(report);
        }
        return ResponseEntity.ok(report);
    }

    /**
     * Signals an active loop sequence to halt processing gracefully before starting its next
     * iteration.
     */
    @PostMapping("/pause/{uuid}")
    public ResponseEntity<Map<String, String>> pauseAnalysis(@PathVariable UUID uuid) {
        log.info("REST request received to pause execution pipeline for task UUID: [{}]", uuid);
        chatAnalysisService.pauseAnalysis(uuid);
        return ResponseEntity.ok(Map.of(
            "message",
            "Pause signal dispatched successfully. Task will stop at its current breakpoint.",
            "taskId", uuid.toString()
        ));
    }

    /**
     * Clears all cached state indicators, purges structural temporary files, and restarts
     * processing from scratch.
     */
    @PostMapping("/restart/{uuid}")
    public ResponseEntity<Map<String, String>> restartAnalysis(@PathVariable UUID uuid) {
        log.info(
            "REST request received to restart execution pipeline from scratch for task UUID: [{}]",
            uuid);
        chatAnalysisService.startOverAnalysis(uuid);
        return ResponseEntity.ok(Map.of(
            "message", "Task restarted successfully from chunk zero.",
            "taskId", uuid.toString()
        ));
    }
}