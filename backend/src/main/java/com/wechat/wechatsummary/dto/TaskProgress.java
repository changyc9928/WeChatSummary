package com.wechat.wechatsummary.dto;

import lombok.Getter;
import lombok.ToString;

/**
 * Data transfer object representing the current state of the batch task.
 */
@Getter
@ToString
public class TaskProgress {

    private final TaskStatus status;
    private final int totalTasks;
    private final int remainingTasks;
    private final int completedTasks;
    private final double progressPercentage;

    public TaskProgress(TaskStatus status, int totalTasks, int remainingTasks) {
        this.status = status;
        this.totalTasks = totalTasks;
        this.remainingTasks = Math.max(0, remainingTasks);
        this.completedTasks = Math.max(0, totalTasks - remainingTasks);
        this.progressPercentage = totalTasks > 0
            ? (double) this.completedTasks / totalTasks * 100
            : 100.0;
    }
}