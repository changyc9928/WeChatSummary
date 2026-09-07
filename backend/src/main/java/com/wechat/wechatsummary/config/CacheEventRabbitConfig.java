package com.wechat.wechatsummary.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated RabbitMQ topology for delayed cache evictions.
 *
 * <p>Completely separate from the media pipelines ({@code media.exchange},
 * {@code image/audio/video/emoji.queue}); media queue behavior is untouched.
 *
 * <p>Delay mechanism: <strong>per-message TTL + dead-lettering</strong>, not the
 * {@code rabbitmq_delayed_message_exchange} plugin (the deployed broker runs plain
 * {@code rabbitmq:latest} with no plugins enabled, and per-message expiration is already used
 * by {@code MediaMessageHandler} retries).
 *
 * <pre>
 * cache.eviction.exchange (topic, durable)
 * ├── cache.eviction.delay.hold --(cache.eviction.delay)   holding pen, no consumer;
 * │       x-dead-letter-exchange = cache.eviction.exchange
 * │       x-dead-letter-routing-key = cache.eviction.ready
 * │       (per-message expiration = cache.eviction.delayed-delay)
 * └── cache.eviction.queue --(cache.eviction.ready)        consumer evicts here
 * </pre>
 *
 * <p>Payloads are JSON strings produced/consumed explicitly with the
 * {@code cacheEventObjectMapper}; no global message-converter change, so media queues keep
 * their plain-string contract. Messages are persistent; exchanges/queues durable.
 */
@Configuration
public class CacheEventRabbitConfig {

    /** Topic exchange for cache eviction traffic. */
    public static final String EXCHANGE = "cache.eviction.exchange";

    /**
     * Holding queue for delayed evictions. Has no consumer; messages expire (per-message TTL set
     * by the producer) and dead-letter to {@link #EXCHANGE} with {@link #EVICTION_ROUTING_KEY}.
     */
    public static final String HOLD_QUEUE = "cache.eviction.delay.hold";

    /** Routing key used to park a message in the delay holding queue. */
    public static final String HOLD_ROUTING_KEY = "cache.eviction.delay";

    /** Queue for matured delayed evictions. */
    public static final String EVICTION_QUEUE = "cache.eviction.queue";

    /** Routing key for matured delayed evictions (also the hold queue's dead-letter key). */
    public static final String EVICTION_ROUTING_KEY = "cache.eviction.ready";

    @Bean
    public TopicExchange cacheEvictionExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue cacheEvictionHoldQueue() {
        return QueueBuilder.durable(HOLD_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", EVICTION_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding cacheEvictionHoldBinding(
            Queue cacheEvictionHoldQueue, TopicExchange cacheEvictionExchange) {
        return BindingBuilder.bind(cacheEvictionHoldQueue)
                .to(cacheEvictionExchange)
                .with(HOLD_ROUTING_KEY);
    }

    @Bean
    public Queue cacheEvictionQueue() {
        return QueueBuilder.durable(EVICTION_QUEUE).build();
    }

    @Bean
    public Binding cacheEvictionBinding(
            Queue cacheEvictionQueue, TopicExchange cacheEvictionExchange) {
        return BindingBuilder.bind(cacheEvictionQueue)
                .to(cacheEvictionExchange)
                .with(EVICTION_ROUTING_KEY);
    }

    /**
     * Isolated listener container for the eviction consumer, separate from the media pipeline
     * factory so cache tuning never affects media throughput and vice versa. Manual
     * acknowledgment: the consumer acks only after the eviction succeeded, so a failed eviction
     * is redelivered by the broker instead of being lost.
     */
    @Bean("cacheEvictionListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory cacheEvictionListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setPrefetchCount(5);
        return factory;
    }

    /**
     * Dedicated JSON mapper for cache eviction payloads. Explicitly configured so serialization
     * never depends on the application's shared {@code ObjectMapper} tuning.
     */
    @Bean("cacheEventObjectMapper")
    public ObjectMapper cacheEventObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
