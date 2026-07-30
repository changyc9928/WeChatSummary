package com.wechat.wechatsummary.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class responsible for setting up the RabbitMQ messaging infrastructure topology.
 * Provisions durable Topic Exchanges, specific media queues, explicit routing keys, and configures
 * the consumer listener container thread factory allocation ceiling and prefetch limits.
 */
@Configuration
public class RabbitConfig {

    /**
     * Shared topic exchange name for all media ingestion pipelines.
     */
    public static final String EXCHANGE = "media.exchange";

    /**
     * Dedicated AMQP queue identifier for image validation and processing operations.
     */
    public static final String IMAGE_QUEUE = "image.queue";

    /**
     * Dedicated AMQP queue identifier for audio transcription operations.
     */
    public static final String AUDIO_QUEUE = "audio.queue";

    /**
     * Binding routing key utilized to target the image processing infrastructure.
     */
    public static final String IMAGE_ROUTING_KEY = "media.image";

    /**
     * Binding routing key utilized to target the whisper audio transcription infrastructure.
     */
    public static final String AUDIO_ROUTING_KEY = "media.audio";

    /**
     * Configures the listener container factory responsible for initializing asynchronous consumer
     * thread pools. Manages scaling behaviors, baseline workers, and prefetch limits to optimize
     * task distribution.
     *
     * @param connectionFactory the primary Spring AMQP connection manager instance
     * @return a configured SimpleRabbitListenerContainerFactory instance
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        // Concurrent Worker Allocation Topology Configuration
        factory.setConcurrentConsumers(3);   // Baseline concurrent consumer worker threads
        factory.setMaxConcurrentConsumers(
            10); // Dynamic expansion scaling ceiling under heavy loads
        factory.setPrefetchCount(
            5);          // Unacknowledged message threshold window limit allocated per consumer

        return factory;
    }

    /**
     * Provisions the shared Topic Exchange allowing fine-grained routing key token selections.
     *
     * @return a durable TopicExchange instance
     */
    @Bean
    public TopicExchange mediaExchange() {
        return new TopicExchange(EXCHANGE);
    }

    // --- Image Queue & Bindings Infrastructure Infrastructure ---

    /**
     * Provisions a durable queue dedicated to handling image data operations.
     *
     * @return a durable image Queue instance
     */
    @Bean
    public Queue imageQueue() {
        return new Queue(IMAGE_QUEUE, true);
    }

    /**
     * Binds the image queue to the media topic exchange using the designated image routing key.
     *
     * @param imageQueue    the configured image queue bean
     * @param mediaExchange the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding imageBinding(Queue imageQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(imageQueue)
            .to(mediaExchange)
            .with(IMAGE_ROUTING_KEY);
    }

    // --- Audio Queue & Bindings Infrastructure Infrastructure ---

    /**
     * Provisions a durable queue dedicated to handling audio voice file transactions.
     *
     * @return a durable audio Queue instance
     */
    @Bean
    public Queue audioQueue() {
        return new Queue(AUDIO_QUEUE, true);
    }

    /**
     * Binds the audio queue to the media topic exchange using the designated audio routing key.
     *
     * @param audioQueue    the configured audio queue bean
     * @param mediaExchange the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding audioBinding(Queue audioQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(audioQueue)
            .to(mediaExchange)
            .with(AUDIO_ROUTING_KEY);
    }
}