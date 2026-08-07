package com.wechat.wechatsummary.dto;

import java.util.List;
import java.util.Map;

/**
 * Structured preview of a cleaned WeChat chat log: group metadata plus the parsed message rows.
 */
public record ChatPreviewResponse(Map<String, String> metadata, List<ChatPreviewRow> rows) {
}