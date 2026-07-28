package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.ChatAnalysisService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatAnalysisController {

    private final ChatAnalysisService chatAnalysisService;

    @PostMapping("/{uuid}")
    public ResponseEntity<?> startAnalysis(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Starting chat analysis pipeline for user UUID: [{}] and task UUID: [{}]", userId, uuid);
        chatAnalysisService.analyzeChatLogAsync(userId, uuid);
        return ResponseEntity.ok(Map.of("status", "RUNNING", "taskId", uuid));
    }

    @GetMapping("/status-pool/{uuid}")
    public ResponseEntity<Map<String, Object>> getStatusAndProgress(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        return ResponseEntity.ok(chatAnalysisService.getStatusAndProgress(userId, uuid));
    }

    @PostMapping("/pause/{uuid}")
    public ResponseEntity<Map<String, String>> pauseAnalysis(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Request received to pause pipeline for user UUID: [{}] and task UUID: [{}]", userId, uuid);
        chatAnalysisService.pauseAnalysis(uuid);
        return ResponseEntity.ok(
            Map.of("message", "Pause signal sent immediately.", "taskId", uuid.toString()));
    }

    @PostMapping("/restart/{uuid}")
    public ResponseEntity<Map<String, String>> restartAnalysis(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Request received to clear and restart user UUID: [{}] and task UUID: [{}]", userId, uuid);
        chatAnalysisService.startOverAnalysis(userId, uuid);
        chatAnalysisService.analyzeChatLogAsync(userId, uuid);
        return ResponseEntity.ok(
            Map.of("message", "Task restarted successfully.", "taskId", uuid.toString()));
    }
}