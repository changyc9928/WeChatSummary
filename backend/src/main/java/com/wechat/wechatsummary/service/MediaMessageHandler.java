package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Handles the shared AMQP consumer lifecycle for media queue messages: payload parsing, abort
 * checks, thread registration, processing, completion reporting, and delayed requeue on transient
 * failures (e.g. HTTP 429 rate limiting) so that media is never silently dropped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaMessageHandler {

    /**
     * Maximum number of automatic delayed retries before a media item is given up (still recorded
     * as completed so the overall task can finish).
     */
    private static final int MAX_RETRIES = 8;

    /**
     * Delay (ms) applied between automatic retries. Combined with the bounded provider throttle this
     * keeps retries spaced out enough to clear provider rate-limit cooldown windows.
     */
    private static final long RETRY_DELAY_MS = 30_000L;

    private static final String RETRY_HEADER = "x-retry-count";

    private final TaskCoordinatorService coordinatorService;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Parses a {@code userId:uuid:filePath} message and runs the given processor against the
     * contained file path. On transient failure the original message is acknowledged and a delayed
     * copy is republished to the same routing key (up to {@link #MAX_RETRIES} times).
     *
     * @param amqpMessage raw AMQP message (used to inspect retry headers)
     * @param mediaType   human-readable media kind used in logs (e.g. "audio", "image")
     * @param processor   downstream processing step keyed by file path
     * @param routingKey  routing key to republish delayed retries to
     */
    public void handle(Message amqpMessage, String mediaType, Consumer<String> processor,
        String routingKey) {
        String message = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        log.info("Received {} message: {}", mediaType, message);
        String[] parts = message.split(":", 3);
        if (parts.length < 3) {
            log.error("Malformed {} queue message payload received: {}", mediaType, message);
            return;
        }

        String uuid = parts[1];
        String filePath = parts[2];

        if (coordinatorService.isAborted(uuid)) {
            log.warn("Task UUID [{}] has been explicitly ABORTED. DROPPING message safely.", uuid);
            return;
        }

        int retryCount = readRetryCount(amqpMessage);
        coordinatorService.registerThread(uuid, Thread.currentThread());

        try {
            processor.accept(filePath);
            coordinatorService.completeTask(uuid, Thread.currentThread());
        } catch (Exception e) {
            log.error("Failed to process {} file, path: {}. Attempt {}/{}.",
                mediaType, filePath, retryCount + 1, MAX_RETRIES + 1, e);

            if (retryCount < MAX_RETRIES) {
                requeueWithDelay(message, routingKey, retryCount + 1);
            } else {
                log.error(
                    "Giving up after {} retries for {} file: {}. Recording as completed to avoid stalling the task.",
                    MAX_RETRIES, mediaType, filePath);
                coordinatorService.completeTask(uuid, Thread.currentThread());
            }
        } finally {
            coordinatorService.unregisterThread(uuid, Thread.currentThread());
        }
    }

    private int readRetryCount(Message amqpMessage) {
        Object header = amqpMessage.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (header instanceof Number) {
            return ((Number) header).intValue();
        }
        return 0;
    }

    private void requeueWithDelay(String payload, String routingKey, int nextRetryCount) {
        try {
            MessagePostProcessor delayProcessor = msg -> {
                msg.getMessageProperties().setExpiration(String.valueOf(RETRY_DELAY_MS));
                msg.getMessageProperties().getHeaders().put(RETRY_HEADER, nextRetryCount);
                return msg;
            };
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, payload, delayProcessor);
            log.info("Requeued message with {}ms delay (retry attempt {}) to routing key [{}]",
                RETRY_DELAY_MS, nextRetryCount, routingKey);
        } catch (Exception e) {
            log.error("Failed to requeue message for delayed retry. It will be dropped.", e);
        }
    }
}
