package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.service.AudioProcessorService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AudioListener {

    private final AudioProcessorService audioSummaryService;
    private final TaskTaskCoordinatorService coordinatorService;

    @RabbitListener(queues = RabbitConfig.AUDIO_QUEUE)
    public void receiveAudio(String message) {
        log.info("Received audio message: {}", message);
        String[] parts = message.split(":", 2);
        if (parts.length < 2) {
            return;
        }

        String uuid = parts[0];
        String filePath = parts[1];

        // 1. Check current global orchestration state
        TaskProgress progress = coordinatorService.getTaskProgress(uuid);
        String status = progress.getStatus();

        // IF PAUSED or NOT_FOUND: Drop the message immediately and do NOT decrement the counter
        if ("PAUSED".equalsIgnoreCase(status) || "NOT_FOUND".equalsIgnoreCase(status)) {
            log.warn(
                "Task context [{}] is currently {}. DROPPING message safely to clear the queue.",
                uuid, status);
            return;
        }

        try {
            audioSummaryService.processAudioSummary(filePath);
            log.info("Successfully processed audio summary for: {}", filePath);
        } catch (Exception e) {
            log.error("Failed to process audio file, path: {}. Skipping and releasing lock.",
                filePath, e);
        } finally {
            // This only executes if the message wasn't dropped above
            coordinatorService.completeTask(uuid);
        }
    }
}