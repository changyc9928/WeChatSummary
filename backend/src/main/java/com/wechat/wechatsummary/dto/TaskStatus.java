package com.wechat.wechatsummary.dto;

/**
 * Enum representing the current execution progress state of a batch task.
 */
public enum TaskStatus {
    IDLING,
    RUNNING,
    PAUSED,
    COMPLETED
}