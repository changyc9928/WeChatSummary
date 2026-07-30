package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.SummaryRequestDTO;
import com.wechat.wechatsummary.entity.ChatSummaryStatus;
import com.wechat.wechatsummary.service.ChatSummaryService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/summary")
@RequiredArgsConstructor
public class ChatSummaryController {

    private final ChatSummaryService chatSummaryService;

    @GetMapping("/preview/{uuid}")
    public ResponseEntity<Map<String, Object>> getChatPreview(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Request received to fetch chat preview table for user UUID: [{}] and task UUID: [{}]",
            userId, uuid);
        Map<String, Object> previewData = chatSummaryService.getChatPreviewData(userId, uuid);
        return ResponseEntity.ok(previewData);
    }

    @PostMapping("/{uuid}")
    public ResponseEntity<?> startSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid,
        @RequestBody(required = false) SummaryRequestDTO requestDTO) {

        LocalDateTime startTime = requestDTO != null ? requestDTO.getStartTime() : null;
        LocalDateTime endTime = requestDTO != null ? requestDTO.getEndTime() : null;

        log.info("Starting chat summary pipeline for user UUID: [{}] and task UUID: [{}] starting time: [{}], end time: [{}]",
            userId, uuid, startTime, endTime);

        chatSummaryService.summarizeChatLogAsync(userId, uuid, startTime, endTime);
        return ResponseEntity.ok(Map.of("status", ChatSummaryStatus.RUNNING, "taskId", uuid));
    }

    @GetMapping("/status-pool/{uuid}")
    public ResponseEntity<Map<String, Object>> getStatusAndProgress(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        return ResponseEntity.ok(chatSummaryService.getStatusAndProgress(userId, uuid));
    }

    @PostMapping("/pause/{uuid}")
    public ResponseEntity<Map<String, String>> pauseSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Request received to pause pipeline for user UUID: [{}] and task UUID: [{}]",
            userId, uuid);
        chatSummaryService.pauseSummary(uuid);
        return ResponseEntity.ok(
            Map.of("message", "Pause signal sent immediately.", "taskId", uuid.toString()));
    }

    @PostMapping("/restart/{uuid}")
    public ResponseEntity<Map<String, String>> restartSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid,
        @RequestBody(required = false) SummaryRequestDTO requestDTO) {

        LocalDateTime startTime = requestDTO != null ? requestDTO.getStartTime() : null;
        LocalDateTime endTime = requestDTO != null ? requestDTO.getEndTime() : null;

        log.info("Request received to clear and restart user UUID: [{}] and task UUID: [{}] starting time: [{}], end time: [{}]",
            userId, uuid, startTime, endTime);

        chatSummaryService.startOverSummary(userId, uuid);
        chatSummaryService.summarizeChatLogAsync(userId, uuid, startTime, endTime);

        return ResponseEntity.ok(
            Map.of("message", "Task restarted successfully.", "taskId", uuid.toString()));
    }
}