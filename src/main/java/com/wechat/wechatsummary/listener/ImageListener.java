package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.service.ImageProcessorService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageListener {

    private final ImageProcessorService imageProcessorService;
    private final TaskTaskCoordinatorService coordinatorService;

    @RabbitListener(queues = RabbitConfig.IMAGE_QUEUE)
    public void receive(String message) {
        log.info("Received image message: {}", message);
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
            imageProcessorService.processImage(filePath);
        } catch (Exception e) {
            log.error("Failed to process image file, path: {}. Skipping and releasing lock.",
                filePath, e);
        } finally {
            coordinatorService.completeTask(uuid);
        }
    }
}
