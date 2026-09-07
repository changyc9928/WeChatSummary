package com.wechat.wechatsummary.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Executes cache evictions against the {@link CacheManager}.
 *
 * <p>Every operation is idempotent: evicting an absent entry (or clearing) is success, and a
 * missing cache region is logged and treated as success. Regions are whitelist-checked so a
 * forged RabbitMQ message can never address an arbitrary cache.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictor {

    private final CacheManager cacheManager;

    /** Executes one eviction instruction. */
    public void execute(CacheEvictionMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (message.cacheName() == null || !CacheNames.ALLOWED.contains(message.cacheName())) {
            throw new IllegalArgumentException("Refusing to touch unknown cache: "
                    + message.cacheName());
        }
        Cache cache = cacheManager.getCache(message.cacheName());
        if (cache == null) {
            log.warn("Cache region [{}] not available; treating eviction as success",
                    message.cacheName());
            return;
        }
        if (message.clearAll()) {
            cache.clear();
            log.info("Cleared cache region [{}]", message.cacheName());
        } else {
            cache.evict(message.cacheKey());
            log.info("Evicted cache entry [{}:{}]", message.cacheName(), message.cacheKey());
        }
    }
}
