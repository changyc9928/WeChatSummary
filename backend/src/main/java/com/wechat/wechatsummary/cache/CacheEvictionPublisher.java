package com.wechat.wechatsummary.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.config.CacheEventRabbitConfig;
import com.wechat.wechatsummary.config.CacheEvictionProperties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Publishes cache eviction instructions to RabbitMQ with the configured delay.
 *
 * <p>At-least-once publishing: every send carries a {@link CorrelationData} and the publisher
 * waits for the broker confirm, retrying with exponential backoff until the broker confirms or
 * the attempt budget ({@code cache.eviction.publish-max-attempts}) is exhausted. Requires
 * {@code spring.rabbitmq.publisher-confirm-type=correlated}, otherwise confirms never arrive
 * and every attempt fails with a confirm timeout.
 *
 * <p>If all attempts fail the error is logged and swallowed — by then the DB transaction has
 * already committed (publishing happens in {@code afterCommit}), so throwing could not roll
 * anything back; it would only mislead the caller. The worst case is an entry staying stale
 * until its TTL expires.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictionPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final CacheEvictionProperties properties;
    private final @Qualifier("cacheEventObjectMapper") ObjectMapper objectMapper;

    /** Requests eviction of one cache entry after the configured delay. */
    public void evict(String cacheName, String cacheKey) {
        if (cacheName == null || cacheName.isBlank()) {
            throw new IllegalArgumentException("cacheName must not be blank");
        }
        if (cacheKey == null || cacheKey.isBlank()) {
            throw new IllegalArgumentException("cacheKey must not be blank");
        }
        send(CacheEvictionMessage.evict(cacheName, cacheKey.trim()));
    }

    /** Requests a whole-region clear after the configured delay. */
    public void clear(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            throw new IllegalArgumentException("cacheName must not be blank");
        }
        send(CacheEvictionMessage.clear(cacheName));
    }

    private void send(CacheEvictionMessage message) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            // Serialization failure is not retryable: the same object would fail again.
            log.warn("Skipping cache eviction for [{}], serialization failed: {}",
                    message.cacheName(), e.toString());
            return;
        }
        final long delayMs = Math.max(1, properties.getDelayedDelay().toMillis());
        final int maxAttempts = Math.max(1, properties.getPublishMaxAttempts());
        final long confirmTimeoutMs =
                Math.max(1, properties.getPublishConfirmTimeout().toMillis());
        long backoffMs = Math.max(0, properties.getPublishRetryBackoff().toMillis());
        final double multiplier = Math.max(1.0, properties.getPublishRetryBackoffMultiplier());

        for (int attempt = 1; ; attempt++) {
            try {
                sendOnce(message, payload, delayMs, confirmTimeoutMs);
                if (attempt > 1) {
                    log.info("Published cache eviction for [{}] on attempt {}/{}",
                            message.cacheName(), attempt, maxAttempts);
                } else if (log.isDebugEnabled()) {
                    log.debug("Published cache eviction for [{}] with {}ms delay",
                            message.cacheName(), delayMs);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while publishing cache eviction for [{}]; giving up",
                        message.cacheName());
                return;
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    log.error("Giving up publishing cache eviction for [{}] after {} attempts; "
                                    + "entry may stay stale until its TTL expires: {}",
                            message.cacheName(), attempt, e.toString());
                    return;
                }
                log.warn("Publish attempt {}/{} failed for [{}], retrying in {}ms: {}",
                        attempt, maxAttempts, message.cacheName(), backoffMs, e.toString());
                try {
                    sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while backing off cache eviction publish for [{}]; "
                            + "giving up", message.cacheName());
                    return;
                }
                backoffMs = (long) (backoffMs * multiplier);
            }
        }
    }

    /**
     * Sends once and waits for the broker confirm.
     *
     * @throws Exception when the send fails or the broker does not confirm in time
     */
    private void sendOnce(
            CacheEvictionMessage message, String payload, long delayMs, long confirmTimeoutMs)
            throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(
                CacheEventRabbitConfig.EXCHANGE,
                CacheEventRabbitConfig.HOLD_ROUTING_KEY,
                payload,
                outgoing -> {
                    outgoing.getMessageProperties()
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    outgoing.getMessageProperties()
                            .setContentType("application/json");
                    // Per-message TTL: the holding queue dead-letters the message to the
                    // eviction queue after this delay. Survives broker + app restarts.
                    outgoing.getMessageProperties()
                            .setExpiration(String.valueOf(delayMs));
                    return outgoing;
                },
                correlation);
        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AmqpException(
                    "Broker confirm failed for cache eviction [" + message.cacheName() + "]",
                    cause);
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "no confirm received" : confirm.getReason();
            throw new AmqpException("Broker did not confirm cache eviction ["
                    + message.cacheName() + "]: " + reason);
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
