package com.wechat.wechatsummary.dto;

import lombok.Getter;
import lombok.ToString;

/**
 * Data transfer object representing the current state of the batch task.
 *
 * <p>The processed Markdown file is the single source of truth for COMPLETED.
 * Redis state and active-thread state only describe incomplete task progress.</p>
 *
 * <p>Use the static factory methods to construct instances with correct semantics
 * rather than relying on constructor argument values to infer state.</p>
 */
@Getter
@ToString
public class TaskProgress {

    private final TaskStatus status;
    private final int totalTasks;
    private final int remainingTasks;
    private final int completedTasks;
    private final double progressPercentage;

    private TaskProgress(TaskStatus status, int totalTasks, int remainingTasks) {
        this.status = status;
        this.totalTasks = Math.max(0, totalTasks);
        this.remainingTasks = Math.max(0, Math.min(remainingTasks, this.totalTasks));
        this.completedTasks = Math.max(0, this.totalTasks - this.remainingTasks);
        this.progressPercentage = computePercentage(this.status, this.totalTasks,
            this.completedTasks);
    }

    // ── Factory methods ───────────────────────────────────────────────────

    public static TaskProgress running(int totalTasks, int remainingTasks) {
        return new TaskProgress(TaskStatus.RUNNING, totalTasks, remainingTasks);
    }

    public static TaskProgress paused(int totalTasks, int remainingTasks) {
        return new TaskProgress(TaskStatus.PAUSED, totalTasks, remainingTasks);
    }

    public static TaskProgress completed(int totalTasks) {
        return new TaskProgress(TaskStatus.COMPLETED, totalTasks, 0);
    }

    public static TaskProgress idling() {
        return new TaskProgress(TaskStatus.IDLING, 0, 0);
    }

    // ── Progress calculation ──────────────────────────────────────────────

    private static double computePercentage(TaskStatus status, int total, int completed) {
        if (status == TaskStatus.COMPLETED) {
            return 100.0;
        }
        return total > 0
            ? (double) completed / total * 100.0
            : 0.0;
    }
}
