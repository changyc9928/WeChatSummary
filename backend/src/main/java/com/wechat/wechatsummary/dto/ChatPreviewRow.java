package com.wechat.wechatsummary.dto;

/**
 * A single parsed line of a chat log preview table.
 */
public record ChatPreviewRow(String lineId, String timestamp, String sender, String content) {
}