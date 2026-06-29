package com.wechat.wechatsummary.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    // 1. 将 Exchange 升级为更通用的媒体交换机
    public static final String EXCHANGE = "media.exchange";

    // 2. 定义图片和音频各自的队列名与路由键
    public static final String IMAGE_QUEUE = "image.queue";
    public static final String AUDIO_QUEUE = "audio.queue";

    public static final String IMAGE_ROUTING_KEY = "media.image";
    public static final String AUDIO_ROUTING_KEY = "media.audio";

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);

        factory.setConcurrentConsumers(3);   // baseline
        factory.setMaxConcurrentConsumers(10); // scaling ceiling
        factory.setPrefetchCount(5); // important for scaling behavior

        return factory;
    }

    // 统一的 Topic 交换机
    @Bean
    public TopicExchange mediaExchange() {
        return new TopicExchange(EXCHANGE);
    }

    // --- 图片队列与绑定 ---
    @Bean
    public Queue imageQueue() {
        return new Queue(IMAGE_QUEUE, true);
    }

    @Bean
    public Binding imageBinding(Queue imageQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(imageQueue)
            .to(mediaExchange)
            .with(IMAGE_ROUTING_KEY);
    }

    // --- 音频队列与绑定 ---
    @Bean
    public Queue audioQueue() {
        return new Queue(AUDIO_QUEUE, true);
    }

    @Bean
    public Binding audioBinding(Queue audioQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(audioQueue)
            .to(mediaExchange)
            .with(AUDIO_ROUTING_KEY);
    }
}