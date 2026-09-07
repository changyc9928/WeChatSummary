package com.wechat.wechatsummary.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.wechat.wechatsummary.cache.CacheEvictionMessage;
import com.wechat.wechatsummary.cache.CacheEvictor;
import com.wechat.wechatsummary.config.CacheEventRabbitConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Executes matured delayed cache evictions with explicit acknowledgment.
 *
 * <p>Settlement contract per delivery:
 * <ul>
 *   <li>eviction succeeded → {@code basicAck}; the message is gone;</li>
 *   <li>unprocessable (malformed JSON, unknown cache region — retrying can never succeed) →
 *   {@code basicNack} without requeue: logged and dropped;</li>
 *   <li>transient failure (e.g. cache backend unavailable) → {@code basicNack} <em>with</em>
 *   requeue on first delivery so the broker redelivers; if the redelivery fails too, the
 *   message is dropped with an error log instead of looping forever.</li>
 * </ul>
 *
 * <p>Eviction itself is idempotent, so the broker redelivery is always safe to re-execute.
 * Never touches business state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictionConsumer {

    private final CacheEvictor evictor;
    private final @Qualifier("cacheEventObjectMapper") ObjectMapper objectMapper;

    @RabbitListener(
            queues = CacheEventRabbitConfig.EVICTION_QUEUE,
            containerFactory = "cacheEvictionListenerContainerFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        boolean redelivered =
                Boolean.TRUE.equals(message.getMessageProperties().getRedelivered());
        byte[] rawBody = message.getBody();
        String json = rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);

        final CacheEvictionMessage eviction;
        try {
            eviction = objectMapper.readValue(json, CacheEvictionMessage.class);
        } catch (Exception e) {
            log.warn("Discarding malformed cache eviction message: {}", e.toString());
            channel.basicNack(tag, false, false);
            return;
        }
        try {
            evictor.execute(eviction);
        } catch (IllegalArgumentException e) {
            log.warn("Discarding cache eviction for unknown cache: {}", e.toString());
            channel.basicNack(tag, false, false);
            return;
        } catch (Exception e) {
            if (!redelivered) {
                log.warn("Transient eviction failure, requesting broker redelivery: {}",
                        e.toString());
                channel.basicNack(tag, false, true);
            } else {
                log.error("Eviction failed again after broker redelivery, dropping message "
                        + "to avoid a poison loop: {}", e.toString());
                channel.basicNack(tag, false, false);
            }
            return;
        }
        channel.basicAck(tag, false);
        if (log.isDebugEnabled()) {
            log.debug("Evicted [{}] after delay (delivery tag {})",
                    describe(eviction), tag);
        }
    }

    private static String describe(CacheEvictionMessage eviction) {
        return eviction.clearAll()
                ? eviction.cacheName() + " *ALL*"
                : eviction.cacheName() + ":" + eviction.cacheKey();
    }
}
