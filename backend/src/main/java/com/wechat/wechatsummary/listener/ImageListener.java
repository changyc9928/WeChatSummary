package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.ImageProcessorService;
import com.wechat.wechatsummary.service.MediaMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageListener {

    private final ImageProcessorService imageProcessorService;
    private final MediaMessageHandler mediaMessageHandler;

    @RabbitListener(queues = RabbitConfig.IMAGE_QUEUE)
    public void receive(String message) {
        mediaMessageHandler.handle(message, "image", imageProcessorService::processImage);
    }
}