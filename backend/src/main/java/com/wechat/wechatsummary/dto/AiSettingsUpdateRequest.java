package com.wechat.wechatsummary.dto;

/**
 * AI provider and preprocessing settings update payload.
 *
 * <p>Every field is optional. A {@code null} (or blank, for text) value keeps the current
 * server-side value; a provided value replaces it after range validation. There is
 * intentionally no per-field "clear": {@code DELETE /api/settings/ai} resets everything
 * back to the environment defaults.
 */
public record AiSettingsUpdateRequest(
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
