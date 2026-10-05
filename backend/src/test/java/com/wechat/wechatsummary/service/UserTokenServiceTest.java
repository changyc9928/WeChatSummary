package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserTokenServiceTest {

    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private UserTokenService service;

    @BeforeEach
    void setUp() {
        service = new UserTokenService(redisTemplate);
        ReflectionTestUtils.setField(service, "tokenTtl", TTL);
    }

    @Test
    void issueStoresUserIdUnderTokenWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String token = service.issue("user-1");

        assertNotNull(token);
        assertFalse(token.isBlank());
        verify(valueOps).set(eq(UserTokenService.TOKEN_KEY_PREFIX + token), eq("user-1"), eq(TTL));
    }

    @Test
    void issueGeneratesDistinctTokens() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        String first = service.issue("user-1");
        String second = service.issue("user-1");

        assertFalse(first.equals(second));
    }

    @Test
    void resolveReturnsUserIdAndRenewsTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(UserTokenService.TOKEN_KEY_PREFIX + "abc")).thenReturn("user-1");

        Optional<String> userId = service.resolveUserId("abc");

        assertTrue(userId.isPresent());
        assertEquals("user-1", userId.get());
        // Sliding expiration: every authenticated request extends the token's life.
        verify(redisTemplate).expire(UserTokenService.TOKEN_KEY_PREFIX + "abc", TTL);
    }

    @Test
    void resolveReturnsEmptyForUnknownTokenAndDoesNotRenew() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(UserTokenService.TOKEN_KEY_PREFIX + "missing")).thenReturn(null);

        assertTrue(service.resolveUserId("missing").isEmpty());
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void revokeDeletesTheTokenKey() {
        service.revoke("abc");

        verify(redisTemplate).delete(UserTokenService.TOKEN_KEY_PREFIX + "abc");
    }
}
