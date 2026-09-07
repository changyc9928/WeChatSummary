package com.wechat.wechatsummary.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.wechat.wechatsummary.cache.CacheEvictionMessage;
import com.wechat.wechatsummary.cache.CacheEvictor;
import com.wechat.wechatsummary.cache.CacheNames;
import com.wechat.wechatsummary.config.CacheEventRabbitConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Explicit acknowledgment contract: ack only after a successful eviction; poison is dropped
 * without requeue; transient failures are redelivered once by the broker, then dropped to
 * avoid a poison loop. Duplicates are harmless (eviction is idempotent).
 */
@ExtendWith(MockitoExtension.class)
class CacheEvictionConsumerTest {

    private static final long TAG = 11L;

    @Mock
    private CacheEvictor evictor;

    @Mock
    private Channel channel;

    private ObjectMapper objectMapper;
    private CacheEvictionConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new CacheEventRabbitConfig().cacheEventObjectMapper();
        consumer = new CacheEvictionConsumer(evictor, objectMapper);
    }

    private Message messageFor(byte[] body, boolean redelivered) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setRedelivered(redelivered);
        return new Message(body, props);
    }

    private byte[] jsonOf(CacheEvictionMessage message) throws Exception {
        return objectMapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validMessage_evictsAndAcks() throws Exception {
        CacheEvictionMessage message =
                CacheEvictionMessage.evict(CacheNames.IMAGE_SUMMARY, "hash-1");

        consumer.onMessage(messageFor(jsonOf(message), false), channel);

        verify(evictor).execute(message);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(TAG, false, true);
        verify(channel, never()).basicNack(TAG, false, false);
    }

    @Test
    void clearMessage_clearsAndAcks() throws Exception {
        CacheEvictionMessage message =
                CacheEvictionMessage.clear(CacheNames.AUDIO_SUMMARY_LIST);

        consumer.onMessage(messageFor(jsonOf(message), false), channel);

        verify(evictor).execute(message);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void duplicateMessages_areHarmless() throws Exception {
        Message message =
                messageFor(jsonOf(CacheEvictionMessage.evict(CacheNames.VIDEO_SUMMARY, "v-1")),
                        false);

        consumer.onMessage(message, channel);
        consumer.onMessage(message, channel);

        verify(evictor, times(2))
                .execute(CacheEvictionMessage.evict(CacheNames.VIDEO_SUMMARY, "v-1"));
        verify(channel, times(2)).basicAck(TAG, false);
    }

    @Test
    void malformedMessage_droppedWithoutRequeue() throws Exception {
        consumer.onMessage(
                messageFor("{not-json".getBytes(StandardCharsets.UTF_8), false), channel);

        verify(evictor, never()).execute(any());
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(TAG, false);
    }

    @Test
    void unknownCache_droppedWithoutRequeue() throws Exception {
        CacheEvictionConsumer strictConsumer = new CacheEvictionConsumer(
                new CacheEvictor(emptyManager()), objectMapper);
        // Hand-crafted JSON: the record constructor would reject this, the wire may not.
        String json = """
                {"cacheName":"arbitrary_cache","cacheKey":"k","clearAll":false}\
                """;

        strictConsumer.onMessage(
                messageFor(json.getBytes(StandardCharsets.UTF_8), false), channel);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(TAG, false);
    }

    @Test
    void transientFailure_firstDelivery_requestsBrokerRedelivery() throws Exception {
        doThrow(new RuntimeException("cache backend down"))
                .when(evictor).execute(any(CacheEvictionMessage.class));

        consumer.onMessage(
                messageFor(jsonOf(CacheEvictionMessage.evict(CacheNames.IMAGE_SUMMARY, "h")),
                        false),
                channel);

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(TAG, false);
    }

    @Test
    void transientFailure_redelivery_dropsToAvoidPoisonLoop() throws Exception {
        doThrow(new RuntimeException("cache backend down"))
                .when(evictor).execute(any(CacheEvictionMessage.class));

        consumer.onMessage(
                messageFor(jsonOf(CacheEvictionMessage.evict(CacheNames.IMAGE_SUMMARY, "h")),
                        true),
                channel);

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(TAG, false);
        verify(channel, never()).basicNack(TAG, false, true);
    }

    @Test
    void missingCacheRegion_acksAsSuccess() throws Exception {
        CacheEvictionConsumer lenientConsumer =
                new CacheEvictionConsumer(new CacheEvictor(emptyManager()), objectMapper);

        lenientConsumer.onMessage(
                messageFor(jsonOf(CacheEvictionMessage.evict(CacheNames.IMAGE_SUMMARY, "h")),
                        false),
                channel);

        verify(channel).basicAck(TAG, false);
    }

    @Test
    void emptyBody_droppedWithoutRequeue() throws Exception {
        consumer.onMessage(messageFor(new byte[0], false), channel);

        verify(evictor, never()).execute(any());
        verify(channel).basicNack(TAG, false, false);
    }

    private static org.springframework.cache.CacheManager emptyManager() {
        var manager = mock(org.springframework.cache.CacheManager.class);
        Mockito.lenient().when(manager.getCache(anyString())).thenReturn(null);
        return manager;
    }
}
