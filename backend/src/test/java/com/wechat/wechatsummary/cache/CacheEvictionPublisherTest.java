package com.wechat.wechatsummary.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.CacheEventRabbitConfig;
import com.wechat.wechatsummary.config.CacheEvictionProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * At-least-once publishing: every send waits for the broker confirm and is retried with
 * backoff; only a permanently failing broker gives up (logged), never throwing into the
 * caller.
 */
@ExtendWith(MockitoExtension.class)
class CacheEvictionPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private CacheEvictionProperties properties;
    private CacheEvictionPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new CacheEventRabbitConfig().cacheEventObjectMapper();
        properties = new CacheEvictionProperties();
        properties.setDelayedDelay(Duration.ofMillis(5000));
        publisher = new CacheEvictionPublisher(rabbitTemplate, properties, objectMapper);
        // Default: broker confirms immediately.
        Mockito.lenient().doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void evict_publishesDurableDelayedMessageAfterConfirm() throws Exception {
        publisher.evict(CacheNames.IMAGE_SUMMARY, "hash-1");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MessagePostProcessor> processorCaptor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(CacheEventRabbitConfig.EXCHANGE),
                eq(CacheEventRabbitConfig.HOLD_ROUTING_KEY),
                bodyCaptor.capture(),
                processorCaptor.capture(),
                any(CorrelationData.class));

        CacheEvictionMessage sent =
                objectMapper.readValue(bodyCaptor.getValue(), CacheEvictionMessage.class);
        assertEquals(CacheNames.IMAGE_SUMMARY, sent.cacheName());
        assertEquals("hash-1", sent.cacheKey());
        assertEquals(false, sent.clearAll());

        Message probe = processorCaptor.getValue().postProcessMessage(
                MessageBuilder.withBody("{}".getBytes()).build());
        assertEquals("5000", probe.getMessageProperties().getExpiration());
        assertEquals(MessageDeliveryMode.PERSISTENT,
                probe.getMessageProperties().getDeliveryMode());
        assertEquals(MessageProperties.CONTENT_TYPE_JSON,
                probe.getMessageProperties().getContentType());
    }

    @Test
    void nackThenAck_retriesOnceAndSucceeds() {
        doAnswer(new org.mockito.stubbing.Answer<Void>() {
            private boolean first = true;

            @Override
            public Void answer(org.mockito.invocation.InvocationOnMock invocation) {
                CorrelationData correlation = invocation.getArgument(4);
                correlation.getFuture().complete(first
                        ? new CorrelationData.Confirm(false, "nack")
                        : new CorrelationData.Confirm(true, null));
                first = false;
                return null;
            }
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        properties.setPublishRetryBackoff(Duration.ofMillis(1));

        assertDoesNotThrow(() -> publisher.evict(CacheNames.AUDIO_SUMMARY, "a-1"));

        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void persistentNack_givesUpAfterMaxAttemptsWithoutThrowing() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        properties.setPublishMaxAttempts(3);
        properties.setPublishRetryBackoff(Duration.ofMillis(1));

        assertDoesNotThrow(() -> publisher.evict(CacheNames.VIDEO_SUMMARY, "v-1"));

        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void brokerThrows_retriesThenGivesUpWithoutThrowing() {
        doThrow(new AmqpException("connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        properties.setPublishMaxAttempts(3);
        properties.setPublishRetryBackoff(Duration.ofMillis(1));

        assertDoesNotThrow(() -> publisher.clear(CacheNames.EMOJI_SUMMARY_LIST));

        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void confirmTimeout_retriesThenGivesUpWithoutThrowing() {
        // Broker never confirms: override the auto-ack stub, futures stay incomplete.
        Mockito.doAnswer(invocation -> null)
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        properties.setPublishMaxAttempts(2);
        properties.setPublishConfirmTimeout(Duration.ofMillis(30));
        properties.setPublishRetryBackoff(Duration.ofMillis(1));

        assertDoesNotThrow(() -> publisher.evict(CacheNames.IMAGE_SUMMARY, "hash-t"));

        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void configuredDelay_isAppliedAsTtl() throws Exception {
        properties.setDelayedDelay(Duration.ofMillis(1234));

        publisher.evict(CacheNames.AUDIO_SUMMARY, "a-1");

        ArgumentCaptor<MessagePostProcessor> processorCaptor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(),
                processorCaptor.capture(), any(CorrelationData.class));
        Message probe = processorCaptor.getValue().postProcessMessage(
                MessageBuilder.withBody("{}".getBytes()).build());
        assertEquals("1234", probe.getMessageProperties().getExpiration());
    }

    @Test
    void clear_publishesWholeRegionClear() throws Exception {
        publisher.clear(CacheNames.VIDEO_SUMMARY_LIST);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), bodyCaptor.capture(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        CacheEvictionMessage sent =
                objectMapper.readValue(bodyCaptor.getValue(), CacheEvictionMessage.class);
        assertEquals(CacheNames.VIDEO_SUMMARY_LIST, sent.cacheName());
        assertNull(sent.cacheKey());
        assertTrue(sent.clearAll());
    }

    @Test
    void blankInputs_rejectedFast() {
        assertThrows(IllegalArgumentException.class,
                () -> publisher.evict("  ", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.evict(CacheNames.IMAGE_SUMMARY, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.clear(null));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
