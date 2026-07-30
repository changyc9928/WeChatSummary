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
        String[] parts = message.split(":", 3); // Split into at most 3 parts
        if (parts.length < 3) {
            log.error("Malformed image queue message payload received: {}", message);
            return;
        }

        String userId = parts[0];
        String uuid = parts[1];
        String filePath = parts[2];

        if (coordinatorService.isAborted(uuid)) {
            log.warn("Task UUID [{}] has been explicitly ABORTED. DROPPING message safely.", uuid);
            return;
        }

        coordinatorService.registerThread(uuid, Thread.currentThread());

        try {
            // Pass userId down so downstream processor can handle path fallbacks securely if needed
            imageProcessorService.processImage(filePath);
        } catch (Exception e) {
            log.error("Failed to process image file, path: {}. Skipping.", filePath, e);
        } finally {
            coordinatorService.completeTask(uuid, Thread.currentThread(), null, null);
        }
    }
}