package com.wechat.wechatsummary.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cache eviction instruction sent over RabbitMQ as JSON.
 *
 * <p>Either a targeted {@code evict(cacheName, cacheKey)} or a whole-region {@code clear}
 * (used only when the affected session cannot be identified reliably). Execution is naturally
 * idempotent, so duplicates and redeliveries are harmless.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheEvictionMessage(
        @JsonProperty("cacheName") String cacheName,
        @JsonProperty("cacheKey") String cacheKey,
        @JsonProperty("clearAll") boolean clearAll) {

    public CacheEvictionMessage {
        if (cacheName == null || cacheName.isBlank()) {
            throw new IllegalArgumentException("cacheName must not be blank");
        }
        if (!clearAll && (cacheKey == null || cacheKey.isBlank())) {
            throw new IllegalArgumentException("cacheKey must not be blank for targeted eviction");
        }
    }

    /** Evict one cache entry. */
    public static CacheEvictionMessage evict(String cacheName, String cacheKey) {
        return new CacheEvictionMessage(cacheName, cacheKey, false);
    }

    /** Clear one whole cache region. */
    public static CacheEvictionMessage clear(String cacheName) {
        return new CacheEvictionMessage(cacheName, null, true);
    }
}
