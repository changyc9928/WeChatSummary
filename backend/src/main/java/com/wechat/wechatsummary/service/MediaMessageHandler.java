package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
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

    private static final String RETRY_HEADER = "x-retry-count";

    private final TaskCoordinatorService coordinatorService;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Parses a {@code userId:uuid:filePath} message and runs the given processor against the
     * contained file path. On transient failure the original message is acknowledged and a delayed
     * copy is parked in the type's retry holding queue (up to {@link #MAX_RETRIES} times).
     *
     * @param amqpMessage raw AMQP message (used to inspect retry headers)
     * @param mediaType   human-readable media kind used in logs (e.g. "audio", "image")
     * @param processor   downstream processing step keyed by file path
     * @param retryRoutingKey routing key of the type's retry holding queue
     */
    public void handle(Message amqpMessage, String mediaType, Consumer<String> processor,
        String retryRoutingKey) {
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
                requeueWithDelay(message, retryRoutingKey, retryCount + 1);
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

    private void requeueWithDelay(String payload, String retryRoutingKey, int nextRetryCount) {
        try {
            MessagePostProcessor delayProcessor = msg -> {
                // Deliberately NO per-message expiration here: the retry holding queue applies
                // a fixed queue-level TTL and dead-letters the message back for redelivery.
                // Setting expiration on a message sitting in a queue WITHOUT a dead-letter
                // exchange silently discards it after the TTL — the task counter is then never
                // decremented and preprocessing stalls forever.
                msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                msg.getMessageProperties().getHeaders().put(RETRY_HEADER, nextRetryCount);
                return msg;
            };
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, retryRoutingKey, payload,
                delayProcessor);
            log.info("Parked message in retry holding queue via [{}] (retry attempt {}/{}); "
                    + "redelivery in {}ms",
                retryRoutingKey, nextRetryCount, MAX_RETRIES + 1, RabbitConfig.RETRY_DELAY_MS);
        } catch (Exception e) {
            log.error("Failed to park message for delayed retry. It will be dropped and the "
                + "task counter will stall one short.", e);
        }
    }
}
