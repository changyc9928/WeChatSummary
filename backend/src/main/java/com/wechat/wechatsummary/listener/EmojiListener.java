package com.wechat.wechatsummary.listener;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.EmojiProcessorService;
import com.wechat.wechatsummary.service.MediaMessageHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmojiListener {

    private final EmojiProcessorService emojiProcessorService;
    private final MediaMessageHandler mediaMessageHandler;

    @RabbitListener(queues = RabbitConfig.EMOJI_QUEUE)
    public void receive(Message message, Channel channel) throws java.io.IOException {
        try {
            mediaMessageHandler.handle(message, "emoji", emojiProcessorService::processImage,
                RabbitConfig.EMOJI_ROUTING_KEY);
        } finally {
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
