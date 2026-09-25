package com.wechat.wechatsummary.dto;

/**
 * AI provider settings update payload.
 *
 * <p>Every field is optional. A {@code null} or blank value keeps the current
 * server-side value; a non-blank value replaces it. There is intentionally no
 * per-field "clear": {@code DELETE /api/settings/ai} resets everything back
 * to the environment defaults.
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
    String transcriptionModel) {
}
