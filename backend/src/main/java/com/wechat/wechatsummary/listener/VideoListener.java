package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.MediaMessageHandler;
import com.wechat.wechatsummary.service.VideoProcessorService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoListener {

    private final VideoProcessorService videoProcessorService;
    private final MediaMessageHandler mediaMessageHandler;

    @RabbitListener(queues = RabbitConfig.VIDEO_QUEUE)
    public void receiveVideo(Message message, Channel channel) throws java.io.IOException {
        try {
            mediaMessageHandler.handle(message, "video", videoProcessorService::processVideoSummary,
                RabbitConfig.VIDEO_RETRY_ROUTING_KEY);
        } finally {
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
