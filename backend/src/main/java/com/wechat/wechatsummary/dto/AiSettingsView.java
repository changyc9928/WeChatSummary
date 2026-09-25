package com.wechat.wechatsummary.dto;

/**
 * AI provider and preprocessing settings as presented to the settings sidebar.
 *
 * <p>Provider slots are named by function; each accepts any OpenAI-compatible provider.
 * Secrets are never returned in full: a configured key appears masked ({@code ••••} + last
 * 4 characters), {@code null} means no key is configured (requests go out without
 * credentials). Base URLs, model names and concurrency numbers are returned in full
 * because they are not sensitive.
 */
public record AiSettingsView(
    String chatApiKey,
    String chatBaseUrl,
    String chatModel,
    String imageApiKey,
    String imageBaseUrl,
    String imageModel,
    String videoApiKey,
    String videoBaseUrl,
    String videoModel,
    String transcriptionApiKey,
    String transcriptionBaseUrl,
    String transcriptionModel,
    Integer workers,
    Integer maxWorkers,
    Integer prefetch,
    Integer aiMaxParallel,
    Integer aiThrottlePercent) {
}
