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
public class MediaSummaryCacheService {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String AUDIO_PREFIX = "audio:summary:";
    private final StringRedisTemplate redisTemplate;

    public Optional<String> getAudioSummary(String hash) {
        String value = redisTemplate.opsForValue().get(AUDIO_PREFIX + hash);
        return Optional.ofNullable(value);
    }

    public void putAudioSummary(String hash, String summary) {
        try {
            redisTemplate.opsForValue().set(
                AUDIO_PREFIX + hash,
                summary,
                TTL
            );
        } catch (Exception e) {
            log.error("Failed to write Redis cache for audio hash={}", hash, e);
        }
    }

    public void evictAudioSummary(String hash) {
        redisTemplate.delete(AUDIO_PREFIX + hash);
    }
}