package com.wechat.wechatsummary.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuration class establishing Spring Cache parameters backed by a Redis infrastructure.
 * Standardizes key/value serialization strategies to human-readable JSON formats and defines
 * differentiated Time-To-Live (TTL) structural properties per functional cache identifier region.
 */
@Configuration
public class CacheConfig {

    /**
     * Standardizes the Redis CacheManager configuration layer. Sets up JSON serialization
     * architectures and hooks up fine-grained custom expiration overrides for media summary
     * regions.
     *
     * @param connectionFactory the active enterprise Redis network connection engine helper
     * @return a fully configured and synchronized unified RedisCacheManager instance
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 1. Establish the clean base default configuration mapping (Swapping messy JDK binary for explicit JSON layouts)
        RedisCacheConfiguration baseConfiguration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofDays(1)) // Global fallback baseline threshold safety net of 1 day
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer())
            );

        // 2. Set up dynamic dedicated custom cache override rules per unique functional data domain region
        Map<String, RedisCacheConfiguration> fineGrainedOverrides = new HashMap<>();

        // Audio transcribes receive high retention footprints due to compute expense rules
        fineGrainedOverrides.put("audio_summary", baseConfiguration.entryTtl(Duration.ofDays(7)));

        // Multi-modal image analysis visual tracking captures are allocated a steady 3-day buffer window
        fineGrainedOverrides.put("image_summary", baseConfiguration.entryTtl(Duration.ofDays(3)));

        // 3. Complete structural instantiation linking custom namespaces straight onto the connection pipeline factory
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(baseConfiguration)
            .withInitialCacheConfigurations(fineGrainedOverrides)
            .build();
    }
}