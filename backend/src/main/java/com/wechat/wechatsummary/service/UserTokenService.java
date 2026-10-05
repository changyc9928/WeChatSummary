package com.wechat.wechatsummary.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages opaque Bearer tokens for authenticated users in Redis.
 *
 * <p>Only a random token is handed to the client; the mapping {@code auth:token:{token} -> userId}
 * stays server-side, so the frontend never needs to know (or send) the user UUID. Tokens use a
 * sliding expiration: every successful lookup renews the TTL, keeping active sessions alive while
 * idle ones expire automatically.
 */
@Service
@RequiredArgsConstructor
public class UserTokenService {

    static final String TOKEN_KEY_PREFIX = "auth:token:";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    @Value("${auth.token-ttl:24h}")
    private Duration tokenTtl;

    /**
     * Issues a new Bearer token for the given user and stores it in Redis with the configured TTL.
     *
     * @param userId the user's UUID primary key
     * @return an opaque, URL-safe token to hand to the client
     */
    public String issue(String userId) {
        String token = generateToken();
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, userId, tokenTtl);
        return token;
    }

    /**
     * Resolves the user id behind a token and renews the token's TTL (sliding expiration).
     *
     * @param token the opaque Bearer token
     * @return the user id, or empty when the token is unknown or expired
     */
    public Optional<String> resolveUserId(String token) {
        String key = TOKEN_KEY_PREFIX + token;
        String userId = redisTemplate.opsForValue().get(key);
        if (userId == null) {
            return Optional.empty();
        }
        redisTemplate.expire(key, tokenTtl);
        return Optional.of(userId);
    }

    /** Revokes a single token (logout). Unknown tokens are ignored. */
    public void revoke(String token) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + token);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
