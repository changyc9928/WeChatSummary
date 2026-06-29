package com.wechat.wechatsummary.service;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageSummaryCacheService {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String PREFIX = "image:summary:";
    private final StringRedisTemplate redisTemplate;

    public Optional<String> get(String hash) {
        String value = redisTemplate.opsForValue().get(PREFIX + hash);
        return Optional.ofNullable(value);
    }

    public void put(String hash, String summary) {
        try {
            redisTemplate.opsForValue().set(
                PREFIX + hash,
                summary,
                TTL
            );
        } catch (Exception e) {
            log.error("Failed to write Redis cache for hash={}", hash, e);
        }
    }

    public void evict(String hash) {
        redisTemplate.delete(PREFIX + hash);
    }
}