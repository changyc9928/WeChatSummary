package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.service.AiSettingsService.EffectivePreprocess;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Applies the preprocessing concurrency settings to the running RabbitMQ consumers.
 *
 * <p>The four media queues (image/audio/video/emoji) share the default listener container
 * factory, so every one of their {@link SimpleMessageListenerContainer}s is scaled together:
 * baseline workers, worker ceiling and prefetch are set live without restarting the backend
 * (prefetch takes effect as consumers recycle). The cache-eviction plumbing uses its own
 * fixed factory and is intentionally left untouched.
 */
@Service
@Slf4j
public class PreprocessConcurrencyService {

    private static final Set<String> MEDIA_QUEUES = Set.of(
        RabbitConfig.IMAGE_QUEUE,
        RabbitConfig.AUDIO_QUEUE,
        RabbitConfig.VIDEO_QUEUE,
        RabbitConfig.EMOJI_QUEUE);

    private final RabbitListenerEndpointRegistry registry;
    private final AiSettingsService settingsService;

    public PreprocessConcurrencyService(
        RabbitListenerEndpointRegistry registry,
        AiSettingsService settingsService) {
        this.registry = registry;
        this.settingsService = settingsService;
    }

    /** Picks up stored overrides on boot (containers start with environment values). */
    @EventListener(ApplicationReadyEvent.class)
    public void applyOnStartup() {
        apply();
    }

    /**
     * Pushes the effective concurrency values into every running media consumer.
     *
     * @return how many listener containers were adjusted
     */
    public int apply() {
        EffectivePreprocess p = settingsService.effectivePreprocess();
        int adjusted = 0;
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        for (MessageListenerContainer container : containers) {
            if (!(container instanceof SimpleMessageListenerContainer simple)) {
                continue;
            }
            List<String> queues = List.of(simple.getQueueNames());
            if (queues.stream().noneMatch(MEDIA_QUEUES::contains)) {
                continue;
            }
            simple.setConcurrentConsumers(p.workers());
            simple.setMaxConcurrentConsumers(p.maxWorkers());
            simple.setPrefetchCount(p.prefetch());
            adjusted++;
        }
        log.info("Preprocessing concurrency applied to {} consumer(s): workers={} maxWorkers={} prefetch={} aiPermits={}",
            adjusted, p.workers(), p.maxWorkers(), p.prefetch(), settingsService.aiPermits());
        return adjusted;
    }
}
