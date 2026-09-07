package com.wechat.wechatsummary.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
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
     * Dedicated AMQP queue identifier for video frame extraction and processing operations.
     */
    public static final String VIDEO_QUEUE = "video.queue";

    /**
     * Dedicated AMQP queue identifier for animated sticker (emoji) processing operations.
     */
    public static final String EMOJI_QUEUE = "emoji.queue";

    /**
     * Binding routing key utilized to target the image processing infrastructure.
     */
    public static final String IMAGE_ROUTING_KEY = "media.image";

    /**
     * Binding routing key utilized to target the whisper audio transcription infrastructure.
     */
    public static final String AUDIO_ROUTING_KEY = "media.audio";

    /**
     * Binding routing key utilized to target the video processing infrastructure.
     */
    public static final String VIDEO_ROUTING_KEY = "media.video";

    /**
     * Binding routing key utilized to target the animated sticker (emoji) processing infrastructure.
     */
    public static final String EMOJI_ROUTING_KEY = "media.emoji";

    /**
     * Delay (ms) between automatic media retries. Kept as a constant (not a property) on
     * purpose: it is baked into the retry-hold queues' {@code x-message-ttl} argument at
     * declaration time, so changing it later would require deleting those queues.
     */
    public static final long RETRY_DELAY_MS = 30_000L;

    /**
     * Holding queue for delayed image retries. Has no consumer; the queue TTL parks the message
     * and dead-letters it back to the image processing queue afterwards.
     */
    public static final String IMAGE_RETRY_HOLD_QUEUE = "image.retry.hold";

    /**
     * Holding queue for delayed audio retries.
     */
    public static final String AUDIO_RETRY_HOLD_QUEUE = "audio.retry.hold";

    /**
     * Holding queue for delayed video retries.
     */
    public static final String VIDEO_RETRY_HOLD_QUEUE = "video.retry.hold";

    /**
     * Holding queue for delayed emoji retries.
     */
    public static final String EMOJI_RETRY_HOLD_QUEUE = "emoji.retry.hold";

    /**
     * Routing key parking a message in the image retry holding queue.
     */
    public static final String IMAGE_RETRY_ROUTING_KEY = "media.image.retry";

    /**
     * Routing key parking a message in the audio retry holding queue.
     */
    public static final String AUDIO_RETRY_ROUTING_KEY = "media.audio.retry";

    /**
     * Routing key parking a message in the video retry holding queue.
     */
    public static final String VIDEO_RETRY_ROUTING_KEY = "media.video.retry";

    /**
     * Routing key parking a message in the emoji retry holding queue.
     */
    public static final String EMOJI_RETRY_ROUTING_KEY = "media.emoji.retry";

    @Value("${rabbit.concurrent-consumers:3}")
    private int concurrentConsumers;

    @Value("${rabbit.max-concurrent-consumers:10}")
    private int maxConcurrentConsumers;

    @Value("${rabbit.prefetch-count:5}")
    private int prefetchCount;

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
        factory.setConcurrentConsumers(concurrentConsumers);
        factory.setMaxConcurrentConsumers(maxConcurrentConsumers);
        factory.setPrefetchCount(prefetchCount);
        // Manual acknowledgment: listeners explicitly ack/nack so failed media can be
        // requeued with a delay instead of being silently dropped.
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

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

    // --- Video Queue & Bindings Infrastructure ---

    /**
     * Provisions a durable queue dedicated to handling video file processing transactions.
     *
     * @return a durable video Queue instance
     */
    @Bean
    public Queue videoQueue() {
        return new Queue(VIDEO_QUEUE, true);
    }

    /**
     * Binds the video queue to the media topic exchange using the designated video routing key.
     *
     * @param videoQueue    the configured video queue bean
     * @param mediaExchange the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding videoBinding(Queue videoQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(videoQueue)
            .to(mediaExchange)
            .with(VIDEO_ROUTING_KEY);
    }

    // --- Emoji Queue & Bindings Infrastructure ---

    /**
     * Provisions a durable queue dedicated to handling animated sticker (emoji) transactions.
     *
     * @return a durable emoji Queue instance
     */
    @Bean
    public Queue emojiQueue() {
        return new Queue(EMOJI_QUEUE, true);
    }

    /**
     * Binds the emoji queue to the media topic exchange using the designated emoji routing key.
     *
     * @param emojiQueue    the configured emoji queue bean
     * @param mediaExchange the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding emojiBinding(Queue emojiQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(emojiQueue)
            .to(mediaExchange)
            .with(EMOJI_ROUTING_KEY);
    }

    // --- Retry Hold Queues (TTL + DLX delayed redelivery) ---
    //
    // Each media type gets a holding queue with a fixed queue-level TTL. Failed messages are
    // parked here and dead-lettered back to their processing queue after RETRY_DELAY_MS.
    //
    // Why not per-message expiration on the processing queue (the previous design)? A message
    // that expires in a queue WITHOUT a dead-letter exchange is silently discarded. Under
    // backlog, a retry parked behind hundreds of messages waits longer than the TTL, expires,
    // and is dropped without a trace — its task counter is never decremented and preprocessing
    // stalls forever (observed as 1169/1170). Queue-level TTL is used (instead of per-message)
    // because every retry shares the same fixed delay.

    /**
     * Provisions the durable holding queue for delayed image retries.
     *
     * @return a durable image retry hold Queue instance
     */
    @Bean
    public Queue imageRetryHoldQueue() {
        return QueueBuilder.durable(IMAGE_RETRY_HOLD_QUEUE)
            .withArgument("x-message-ttl", (int) RETRY_DELAY_MS)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", IMAGE_ROUTING_KEY)
            .build();
    }

    /**
     * Binds the image retry holding queue to the media topic exchange.
     *
     * @param imageRetryHoldQueue the configured image retry hold queue bean
     * @param mediaExchange       the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding imageRetryHoldBinding(
        Queue imageRetryHoldQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(imageRetryHoldQueue)
            .to(mediaExchange)
            .with(IMAGE_RETRY_ROUTING_KEY);
    }

    /**
     * Provisions the durable holding queue for delayed audio retries.
     *
     * @return a durable audio retry hold Queue instance
     */
    @Bean
    public Queue audioRetryHoldQueue() {
        return QueueBuilder.durable(AUDIO_RETRY_HOLD_QUEUE)
            .withArgument("x-message-ttl", (int) RETRY_DELAY_MS)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", AUDIO_ROUTING_KEY)
            .build();
    }

    /**
     * Binds the audio retry holding queue to the media topic exchange.
     *
     * @param audioRetryHoldQueue the configured audio retry hold queue bean
     * @param mediaExchange       the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding audioRetryHoldBinding(
        Queue audioRetryHoldQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(audioRetryHoldQueue)
            .to(mediaExchange)
            .with(AUDIO_RETRY_ROUTING_KEY);
    }

    /**
     * Provisions the durable holding queue for delayed video retries.
     *
     * @return a durable video retry hold Queue instance
     */
    @Bean
    public Queue videoRetryHoldQueue() {
        return QueueBuilder.durable(VIDEO_RETRY_HOLD_QUEUE)
            .withArgument("x-message-ttl", (int) RETRY_DELAY_MS)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", VIDEO_ROUTING_KEY)
            .build();
    }

    /**
     * Binds the video retry holding queue to the media topic exchange.
     *
     * @param videoRetryHoldQueue the configured video retry hold queue bean
     * @param mediaExchange       the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding videoRetryHoldBinding(
        Queue videoRetryHoldQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(videoRetryHoldQueue)
            .to(mediaExchange)
            .with(VIDEO_RETRY_ROUTING_KEY);
    }

    /**
     * Provisions the durable holding queue for delayed emoji retries.
     *
     * @return a durable emoji retry hold Queue instance
     */
    @Bean
    public Queue emojiRetryHoldQueue() {
        return QueueBuilder.durable(EMOJI_RETRY_HOLD_QUEUE)
            .withArgument("x-message-ttl", (int) RETRY_DELAY_MS)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", EMOJI_ROUTING_KEY)
            .build();
    }

    /**
     * Binds the emoji retry holding queue to the media topic exchange.
     *
     * @param emojiRetryHoldQueue the configured emoji retry hold queue bean
     * @param mediaExchange       the centralized media topic exchange bean
     * @return a configured Binding instance
     */
    @Bean
    public Binding emojiRetryHoldBinding(
        Queue emojiRetryHoldQueue, TopicExchange mediaExchange) {
        return BindingBuilder
            .bind(emojiRetryHoldQueue)
            .to(mediaExchange)
            .with(EMOJI_RETRY_ROUTING_KEY);
    }
}