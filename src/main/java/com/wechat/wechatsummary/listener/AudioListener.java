package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
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
            audioSummaryService.processAudioSummary(filePath);
            log.info("Successfully processed audio summary for: {}", filePath);
        } catch (Exception e) {
            log.error("Failed to process audio file, path: {}. Skipping.", filePath, e);
        } finally {
            // 3. Clean up thread registration and decrement progress
            coordinatorService.completeTask(uuid, Thread.currentThread(), null, null);
        }
    }
}