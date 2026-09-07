package com.wechat.wechatsummary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

/**
 * Guards the cache eviction topology: durable exchange/queues, correct TTL/DLX wiring for the
 * delay mechanism, and strict separation from the media pipeline queues.
 */
class CacheEventTopologyTest {

    private final CacheEventRabbitConfig config = new CacheEventRabbitConfig();

    @Test
    void exchangeIsDurable() {
        assertEquals(CacheEventRabbitConfig.EXCHANGE, config.cacheEvictionExchange().getName());
        assertTrue(config.cacheEvictionExchange().isDurable());
    }

    @Test
    void holdQueue_deadLettersMaturedMessagesToEvictionQueue() {
        Queue hold = config.cacheEvictionHoldQueue();

        assertTrue(hold.isDurable());
        // The core of the TTL/DLX delay: expiry routes back to the topic exchange on the
        // eviction-ready key (no delayed-message-exchange plugin required).
        assertEquals(CacheEventRabbitConfig.EXCHANGE,
                hold.getArguments().get("x-dead-letter-exchange"));
        assertEquals(CacheEventRabbitConfig.EVICTION_ROUTING_KEY,
                hold.getArguments().get("x-dead-letter-routing-key"));

        Binding holdBinding =
                config.cacheEvictionHoldBinding(hold, config.cacheEvictionExchange());
        assertEquals(CacheEventRabbitConfig.HOLD_ROUTING_KEY, holdBinding.getRoutingKey());
        Binding evictionBinding =
                config.cacheEvictionBinding(config.cacheEvictionQueue(),
                        config.cacheEvictionExchange());
        assertEquals(CacheEventRabbitConfig.EVICTION_ROUTING_KEY, evictionBinding.getRoutingKey());
    }

    @Test
    void evictionQueueIsDurable() {
        assertTrue(config.cacheEvictionQueue().isDurable());
    }

    @Test
    void cacheTopologyIsSeparateFromMediaQueues() {
        var cacheQueues = Map.of(
                CacheEventRabbitConfig.HOLD_QUEUE, config.cacheEvictionHoldQueue(),
                CacheEventRabbitConfig.EVICTION_QUEUE, config.cacheEvictionQueue());
        var mediaQueues = java.util.Set.of(
                RabbitConfig.IMAGE_QUEUE, RabbitConfig.AUDIO_QUEUE,
                RabbitConfig.VIDEO_QUEUE, RabbitConfig.EMOJI_QUEUE);

        for (var entry : cacheQueues.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().getName());
            assertTrue(!mediaQueues.contains(entry.getValue().getName()),
                    "cache queue collides with media queue: " + entry.getKey());
        }
        assertNotEquals(RabbitConfig.EXCHANGE, CacheEventRabbitConfig.EXCHANGE);
        assertEquals(2, cacheQueues.values().stream()
                .map(Queue::getName)
                .collect(Collectors.toSet()).size(), "queue names must be unique");
    }
}
