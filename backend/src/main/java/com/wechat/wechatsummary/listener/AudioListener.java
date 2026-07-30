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

    private final AudioProcessorService audioProcessorService;
    private final TaskTaskCoordinatorService coordinatorService;

    @RabbitListener(queues = RabbitConfig.AUDIO_QUEUE)
    public void receiveAudio(String message) {
        log.info("Received audio message: {}", message);
        String[] parts = message.split(":", 3);
        if (parts.length < 3) {
            log.error("Malformed audio queue message payload received: {}", message);
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
            audioProcessorService.processAudioSummary(filePath);
        } catch (Exception e) {
            log.error("Failed to process audio file, path: {}. Skipping.", filePath, e);
        } finally {
            coordinatorService.completeTask(uuid, Thread.currentThread(), null, null);
        }
    }
}