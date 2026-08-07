package com.wechat.wechatsummary.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.beans.factory.annotation.Value;

/**
 * Configuration class establishing Spring Cache parameters backed by a Redis infrastructure.
 * Standardizes key/value serialization strategies to human-readable JSON formats and defines
 * differentiated Time-To-Live (TTL) structural properties per functional cache identifier region.
 */
@Configuration
public class CacheConfig {

    @Value("${cache.ttl-default:1d}")
    private Duration ttlDefault;

    @Value("${cache.ttl-audio:7d}")
    private Duration ttlAudio;

    @Value("${cache.ttl-image:3d}")
    private Duration ttlImage;

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

        // --- ADDED: Custom ObjectMapper to handle Java 8 Date/Time types ---
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // Crucial for GenericJackson2JsonRedisSerializer to accurately store class type metadata inside the JSON
        objectMapper.activateDefaultTyping(
            objectMapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(
            objectMapper);
        // -----------------------------------------------------------------

        // 1. Establish the clean base default configuration mapping (Swapping messy JDK binary for explicit JSON layouts)
        RedisCacheConfiguration baseConfiguration = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttlDefault)
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    jsonSerializer) // MODIFIED: Using the custom serializer here
            );

        // 2. Set up dynamic dedicated custom cache override rules per unique functional data domain region
        Map<String, RedisCacheConfiguration> fineGrainedOverrides = new HashMap<>();

        // Audio transcribes receive high retention footprints due to compute expense rules
        fineGrainedOverrides.put("audio_summary", baseConfiguration.entryTtl(ttlAudio));

        // Multi-modal image analysis visual tracking captures are allocated a steady buffer window
        fineGrainedOverrides.put("image_summary", baseConfiguration.entryTtl(ttlImage));

        // 3. Complete structural instantiation linking custom namespaces straight onto the connection pipeline factory
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(baseConfiguration)
            .withInitialCacheConfigurations(fineGrainedOverrides)
            .build();
    }
}