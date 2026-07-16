package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.ChatAnalysisService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatAnalysisController {

    private final ChatAnalysisService chatAnalysisService;

    @PostMapping("/{uuid}")
    public ResponseEntity<?> startAnalysis(@PathVariable UUID uuid) {
        log.info("Starting chat analysis pipeline for task UUID: [{}]", uuid);
        chatAnalysisService.analyzeChatLogAsync(uuid);
        return ResponseEntity.ok(Map.of("status", "RUNNING", "taskId", uuid));
    }

    @GetMapping("/status-pool/{uuid}")
    public ResponseEntity<Map<String, Object>> getStatusAndProgress(@PathVariable UUID uuid) {
        return ResponseEntity.ok(chatAnalysisService.getStatusAndProgress(uuid));
    }

    @PostMapping("/pause/{uuid}")
    public ResponseEntity<Map<String, String>> pauseAnalysis(@PathVariable UUID uuid) {
        log.info("Request received to pause pipeline for task UUID: [{}]", uuid);
        chatAnalysisService.pauseAnalysis(uuid);
        return ResponseEntity.ok(Map.of("message", "Pause signal sent immediately.", "taskId", uuid.toString()));
    }

    @PostMapping("/restart/{uuid}")
    public ResponseEntity<Map<String, String>> restartAnalysis(@PathVariable UUID uuid) {
        log.info("Request received to clear and restart task UUID: [{}]", uuid);
        chatAnalysisService.startOverAnalysis(uuid);
        chatAnalysisService.analyzeChatLogAsync(uuid);
        return ResponseEntity.ok(Map.of("message", "Task restarted successfully.", "taskId", uuid.toString()));
    }
}