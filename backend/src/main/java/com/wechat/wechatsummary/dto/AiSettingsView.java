package com.wechat.wechatsummary.dto;

/**
 * AI provider settings as presented to the settings sidebar.
 *
 * <p>Slots are named by function; each accepts any OpenAI-compatible provider. Secrets are
 * never returned in full: a configured key appears masked ({@code ••••} + last 4 characters),
 * {@code null} means no key is configured (requests go out without credentials). Base URLs
 * and model names are returned in full because they are not sensitive.
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
    String transcriptionModel) {
}
