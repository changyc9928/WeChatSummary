package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.AudioSummaryService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AudioListener {

    private final AudioSummaryService audioSummaryService;
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

        try {
            audioSummaryService.processAudioSummary(filePath);
            log.info("Successfully processed audio summary for: {}", filePath);
        } catch (Exception e) {
            // 捕获异常，打印日志。注意：这里不往外抛异常了，代表我们主动“跳过”并认领了这个失败任务
            log.error("Failed to process audio file, path: {}. Skipping and releasing lock.",
                filePath, e);
        } finally {
            // 无论成功还是失败，都必须扣减计数器，确保整个 UUID 链路不会因为单点失败而卡死
            coordinatorService.completeTask(uuid);
        }
    }
}