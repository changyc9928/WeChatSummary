package com.wechat.wechatsummary.dto;

import com.wechat.wechatsummary.entity.ChatSummaryStatus;

/**
 * Acknowledgment returned by endpoints that start, pause or restart a task.
 */
public record TaskAckResponse(String taskId, ChatSummaryStatus status, String message) {
}