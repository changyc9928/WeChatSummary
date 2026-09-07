package com.wechat.wechatsummary.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the delayed safety-net eviction.
 *
 * <p>YAML example:
 * <pre>
 * cache:
 *   eviction:
 *     delayed-delay: 5000
 * </pre>
 * Plain numbers bind as milliseconds; {@code 5s} style values also work.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "cache.eviction")
public class CacheEvictionProperties {

    /**
     * Delay between publishing a cache eviction and the consumer executing it. Covers the race
     * where a stale DB read repopulates the cache right after the write commits.
     */
    private Duration delayedDelay = Duration.ofSeconds(5);

    /**
     * Max publish attempts per eviction (initial try + retries). The publisher waits for a
     * broker confirm on every attempt, so this bounds the worst-case added latency when the
     * broker is unreachable.
     */
    private int publishMaxAttempts = 5;

    /** How long to wait for one broker publish-confirm before treating the attempt as failed. */
    private Duration publishConfirmTimeout = Duration.ofSeconds(5);

    /** Base backoff between publish retries; doubled after every failed attempt. */
    private Duration publishRetryBackoff = Duration.ofMillis(200);

    /** Multiplier applied to the backoff after every failed attempt. */
    private double publishRetryBackoffMultiplier = 2.0;
}
