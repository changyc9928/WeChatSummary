package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
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

        // 1. Check directly if the abort flag key exists in Redis
        if (coordinatorService.isAborted(uuid)) {
            log.warn(
                "Task UUID [{}] has been explicitly ABORTED. DROPPING message safely.",
                uuid);
            return;
        }

        // 2. Register current thread with the coordinator
        coordinatorService.registerThread(uuid, Thread.currentThread());

        try {
            imageProcessorService.processImage(filePath);
        } catch (Exception e) {
            log.error("Failed to process image file, path: {}. Skipping.", filePath, e);
        } finally {
            // 3. Clean up thread registration and decrement progress
            coordinatorService.completeTask(uuid, Thread.currentThread(), null, null);
        }
    }
}