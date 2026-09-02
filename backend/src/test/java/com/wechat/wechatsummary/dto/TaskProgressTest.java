package com.wechat.wechatsummary.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskProgressTest {

    @Test
    void idlingReportsZeroPercent() {
        TaskProgress p = TaskProgress.idling();
        assertEquals(TaskStatus.IDLING, p.getStatus());
        assertEquals(0, p.getTotalTasks());
        assertEquals(0, p.getRemainingTasks());
        assertEquals(0, p.getCompletedTasks());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void runningWithCountsCalculatesPercentage() {
        TaskProgress p = TaskProgress.running(100, 40);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(100, p.getTotalTasks());
        assertEquals(40, p.getRemainingTasks());
        assertEquals(60, p.getCompletedTasks());
        assertEquals(60.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void runningWithZeroTotalReportsZeroPercent() {
        TaskProgress p = TaskProgress.running(0, 0);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void runningWithRemainingEqualTotalReportsZeroPercent() {
        TaskProgress p = TaskProgress.running(100, 100);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void runningWithRemainingZeroReportsHundredPercent() {
        TaskProgress p = TaskProgress.running(100, 0);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void pausedWithCountsCalculatesPercentage() {
        TaskProgress p = TaskProgress.paused(100, 40);
        assertEquals(TaskStatus.PAUSED, p.getStatus());
        assertEquals(60.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void pausedWithZeroTotalReportsZeroPercent() {
        TaskProgress p = TaskProgress.paused(0, 0);
        assertEquals(TaskStatus.PAUSED, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void completedAlwaysReportsHundredPercent() {
        TaskProgress p = TaskProgress.completed(50);
        assertEquals(TaskStatus.COMPLETED, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
        assertEquals(0, p.getRemainingTasks());
    }

    @Test
    void completedWithZeroTotalStillReportsHundredPercent() {
        TaskProgress p = TaskProgress.completed(0);
        assertEquals(TaskStatus.COMPLETED, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void remainingClampedToTotal() {
        TaskProgress p = TaskProgress.running(50, 999);
        assertEquals(50, p.getRemainingTasks());
        assertEquals(0, p.getCompletedTasks());
    }

    @Test
    void negativeCountsClampedToZero() {
        TaskProgress p = TaskProgress.running(-10, -5);
        assertEquals(0, p.getTotalTasks());
        assertEquals(0, p.getRemainingTasks());
        assertEquals(0, p.getCompletedTasks());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }
}
