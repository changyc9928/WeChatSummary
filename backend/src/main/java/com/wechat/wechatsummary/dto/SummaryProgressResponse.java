package com.wechat.wechatsummary.dto;

import com.wechat.wechatsummary.entity.ChatSummaryStatus;
import java.util.UUID;

/**
 * Current state of an asynchronous chat summary task, used by the status polling endpoint.
 */
public record SummaryProgressResponse(UUID taskId, ChatSummaryStatus status, double progress,
                                      String result, String errorMessage) {
}