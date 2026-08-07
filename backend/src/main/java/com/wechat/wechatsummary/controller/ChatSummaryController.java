package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.dto.ChatPreviewResponse;
import com.wechat.wechatsummary.dto.SummaryProgressResponse;
import com.wechat.wechatsummary.dto.SummaryRequestDTO;
import com.wechat.wechatsummary.dto.TaskAckResponse;
import com.wechat.wechatsummary.entity.ChatSummaryStatus;
import com.wechat.wechatsummary.service.ChatSummaryService;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/summary")
@RequiredArgsConstructor
@Slf4j
public class ChatSummaryController {

    private final ChatSummaryService chatSummaryService;

    @GetMapping("/preview/{uuid}")
    public ApiResponse<ChatPreviewResponse> getChatPreview(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info(
            "Request received to fetch chat preview table for user UUID: [{}] and task UUID: [{}]",
            userId, uuid);
        return ApiResponse.success(chatSummaryService.getChatPreviewData(userId, uuid));
    }

    @PostMapping("/{uuid}")
    public ApiResponse<TaskAckResponse> startSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid,
        @RequestBody(required = false) SummaryRequestDTO requestDTO) {

        LocalDateTime startTime = requestDTO != null ? requestDTO.getStartTime() : null;
        LocalDateTime endTime = requestDTO != null ? requestDTO.getEndTime() : null;

        log.info(
            "Starting chat summary pipeline for user UUID: [{}] and task UUID: [{}] starting time: [{}], end time: [{}]",
            userId, uuid, startTime, endTime);

        chatSummaryService.summarizeChatLogAsync(userId, uuid, startTime, endTime);
        return ApiResponse.success("Summary pipeline started successfully",
            new TaskAckResponse(uuid.toString(), ChatSummaryStatus.RUNNING,
                "Summary pipeline started"));
    }

    @GetMapping("/status-pool/{uuid}")
    public ApiResponse<SummaryProgressResponse> getStatusAndProgress(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        return ApiResponse.success(chatSummaryService.getStatusAndProgress(userId, uuid));
    }

    @PostMapping("/pause/{uuid}")
    public ApiResponse<TaskAckResponse> pauseSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid) {
        log.info("Request received to pause pipeline for user UUID: [{}] and task UUID: [{}]",
            userId, uuid);
        chatSummaryService.pauseSummary(uuid);
        return ApiResponse.success("Pause signal sent immediately",
            new TaskAckResponse(uuid.toString(), ChatSummaryStatus.PAUSED,
                "Pause signal sent immediately"));
    }

    @PostMapping("/restart/{uuid}")
    public ApiResponse<TaskAckResponse> restartSummary(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable UUID uuid,
        @RequestBody(required = false) SummaryRequestDTO requestDTO) {

        LocalDateTime startTime = requestDTO != null ? requestDTO.getStartTime() : null;
        LocalDateTime endTime = requestDTO != null ? requestDTO.getEndTime() : null;

        log.info(
            "Request received to clear and restart user UUID: [{}] and task UUID: [{}] starting time: [{}], end time: [{}]",
            userId, uuid, startTime, endTime);

        chatSummaryService.startOverSummary(userId, uuid);
        chatSummaryService.summarizeChatLogAsync(userId, uuid, startTime, endTime);

        return ApiResponse.success("Task restarted successfully",
            new TaskAckResponse(uuid.toString(), ChatSummaryStatus.RUNNING,
                "Task restarted successfully"));
    }
}