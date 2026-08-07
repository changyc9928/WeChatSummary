package com.wechat.wechatsummary.dto;

/**
 * Result of an uploaded ZIP archive: the assigned session workspace UUID.
 */
public record UploadSessionResponse(String sessionId) {
}