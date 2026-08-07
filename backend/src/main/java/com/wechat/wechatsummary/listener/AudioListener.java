package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.AudioProcessorService;
import com.wechat.wechatsummary.service.MediaMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AudioListener {

    private final AudioProcessorService audioProcessorService;
    private final MediaMessageHandler mediaMessageHandler;

    @RabbitListener(queues = RabbitConfig.AUDIO_QUEUE)
    public void receiveAudio(String message) {
        mediaMessageHandler.handle(message, "audio", audioProcessorService::processAudioSummary);
    }
}