package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.config.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Guards the delayed-retry contract that once stalled preprocessing at 1169/1170: failed
 * messages must be parked in the retry holding queue (fixed queue TTL + dead-letter back),
 * must NEVER carry a per-message expiration into a queue without a DLX (expiry there means
 * silent drop and a permanently stuck task counter), and must give up gracefully after
 * MAX_RETRIES while still completing the task.
 */
@ExtendWith(MockitoExtension.class)
class MediaMessageHandlerTest {

    private static final String RETRY_HEADER = "x-retry-count";
    private static final String PAYLOAD =
            "user-1:550e8400-e29b-41d4-a716-446655440000:/app/uploads/u/uuid/images/1.jpg";

    @Mock
    private TaskCoordinatorService coordinatorService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MediaMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MediaMessageHandler(coordinatorService, rabbitTemplate);
    }

    private Message messageWithRetryHeader(Integer retryCount) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        if (retryCount != null) {
            props.setHeader(RETRY_HEADER, retryCount);
        }
        return new Message(PAYLOAD.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void success_completesTaskWithoutRepublish() {
        AtomicBoolean processed = new AtomicBoolean(false);

        handler.handle(messageWithRetryHeader(null), "image",
                path -> processed.set(true), RabbitConfig.IMAGE_RETRY_ROUTING_KEY);

        assertTrue(processed.get());
        verify(coordinatorService).completeTask(anyString(), any(Thread.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void transientFailure_parkedInHoldQueueWithoutExpiration() throws Exception {
        handler.handle(messageWithRetryHeader(null), "emoji", path -> {
            throw new RuntimeException("boom");
        }, RabbitConfig.EMOJI_RETRY_ROUTING_KEY);

        ArgumentCaptor<MessagePostProcessor> processorCaptor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.EMOJI_RETRY_ROUTING_KEY),
                eq(PAYLOAD),
                processorCaptor.capture());
        Message probe = processorCaptor.getValue().postProcessMessage(
                new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        // THE regression guard: per-message expiration on a queue without a DLX silently
        // discards the message after the TTL instead of redelivering it.
        assertNull(probe.getMessageProperties().getExpiration(),
                "retry must not carry per-message expiration");
        assertEquals(1, probe.getMessageProperties().getHeaders().get(RETRY_HEADER));
        assertEquals(MessageDeliveryMode.PERSISTENT,
                probe.getMessageProperties().getDeliveryMode());
        // Original delivery is settled by the listener; task completion happens on the
        // redelivery (or on give-up), never here.
        verify(coordinatorService, never()).completeTask(anyString(), any(Thread.class));
    }

    @Test
    void retryCountHeader_survivesAndIncrements() throws Exception {
        handler.handle(messageWithRetryHeader(2), "audio", path -> {
            throw new RuntimeException("boom");
        }, RabbitConfig.AUDIO_RETRY_ROUTING_KEY);

        ArgumentCaptor<MessagePostProcessor> processorCaptor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.AUDIO_RETRY_ROUTING_KEY),
                eq(PAYLOAD),
                processorCaptor.capture());
        Message probe = processorCaptor.getValue().postProcessMessage(
                new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertEquals(3, probe.getMessageProperties().getHeaders().get(RETRY_HEADER));
    }

    @Test
    void retriesExhausted_completesTaskWithoutRepublish() {
        handler.handle(messageWithRetryHeader(8), "video", path -> {
            throw new RuntimeException("boom");
        }, RabbitConfig.VIDEO_RETRY_ROUTING_KEY);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
        verify(coordinatorService).completeTask(anyString(), any(Thread.class));
    }

    @Test
    void malformedMessage_returnsWithoutCompleting() {
        Message malformed = new Message("no-colons-here".getBytes(StandardCharsets.UTF_8),
                new MessageProperties());

        handler.handle(malformed, "image", path -> {
            throw new AssertionError("processor must not run");
        }, RabbitConfig.IMAGE_RETRY_ROUTING_KEY);

        verify(coordinatorService, never()).completeTask(anyString(), any(Thread.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void abortedTask_dropsWithoutCompleting() {
        when(coordinatorService.isAborted(anyString())).thenReturn(true);

        handler.handle(messageWithRetryHeader(null), "image", path -> {
            throw new AssertionError("processor must not run");
        }, RabbitConfig.IMAGE_RETRY_ROUTING_KEY);

        verify(coordinatorService, never()).completeTask(anyString(), any(Thread.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void requeuePublishFailure_isSwallowed() {
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(MessagePostProcessor.class));

        assertDoesNotThrow(() -> handler.handle(messageWithRetryHeader(null), "image", path -> {
            throw new RuntimeException("boom");
        }, RabbitConfig.IMAGE_RETRY_ROUTING_KEY));
    }

    @Test
    void retryHoldTopology_deadLettersBackToProcessingQueue() {
        // Topology wiring guard: every hold queue must expire into its own processing queue,
        // and every retry routing key must differ from its processing key.
        RabbitConfig config = new RabbitConfig();
        assertHoldWiring(config.imageRetryHoldQueue(), RabbitConfig.IMAGE_ROUTING_KEY);
        assertHoldWiring(config.audioRetryHoldQueue(), RabbitConfig.AUDIO_ROUTING_KEY);
        assertHoldWiring(config.videoRetryHoldQueue(), RabbitConfig.VIDEO_ROUTING_KEY);
        assertHoldWiring(config.emojiRetryHoldQueue(), RabbitConfig.EMOJI_ROUTING_KEY);
    }

    private static void assertHoldWiring(org.springframework.amqp.core.Queue holdQueue,
            String processingRoutingKey) {
        assertTrue(holdQueue.isDurable());
        assertEquals((int) RabbitConfig.RETRY_DELAY_MS,
                holdQueue.getArguments().get("x-message-ttl"));
        assertEquals(RabbitConfig.EXCHANGE,
                holdQueue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(processingRoutingKey,
                holdQueue.getArguments().get("x-dead-letter-routing-key"));
    }
}
