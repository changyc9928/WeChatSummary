package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.config.TaskConfig;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

/**
 * Guards the watchdog that guarantees a preprocessing batch can never stay stuck forever.
 *
 * <p>The regression: the compiled markdown is gated on a Redis counter reaching zero, and a lost
 * media message leaves that counter permanently above zero, so the UI reports the session as running
 * at 99% indefinitely and the only escape is a manual restart. The watchdog must close out a batch
 * that provably cannot progress, while leaving anything still in flight alone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreprocessWatchdogTest {

    private static final String UUID = "stalled-uuid";
    private static final String USER_ID = "user-1";
    private static final long GRACE_MILLIS = 20 * 60 * 1000L;

    @Mock
    private TaskCoordinatorService coordinatorService;

    @Mock
    private AmqpAdmin amqpAdmin;

    private PreprocessWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new PreprocessWatchdog(coordinatorService, amqpAdmin);
    }

    private void allQueuesEmpty() {
        for (String q : new String[]{
            RabbitConfig.IMAGE_QUEUE, RabbitConfig.AUDIO_QUEUE,
            RabbitConfig.VIDEO_QUEUE, RabbitConfig.EMOJI_QUEUE,
            RabbitConfig.IMAGE_RETRY_HOLD_QUEUE, RabbitConfig.AUDIO_RETRY_HOLD_QUEUE,
            RabbitConfig.VIDEO_RETRY_HOLD_QUEUE, RabbitConfig.EMOJI_RETRY_HOLD_QUEUE}) {
            when(amqpAdmin.getQueueInfo(q)).thenReturn(new QueueInformation(q, 0, 1));
        }
    }

    private void queueWithMessages(String queue, long count) {
        when(amqpAdmin.getQueueInfo(queue)).thenReturn(new QueueInformation(queue, count, 1));
    }

    @Test
    void noTasks_doesNothing() {
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of());

        assertEquals(0, watchdog.sweep());
        verify(amqpAdmin, never()).getQueueInfo(anyString());
    }

    @Test
    void stalledBatch_isForceCompleted() {
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.remainingTasks(UUID)).thenReturn(1);
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(true);
        when(coordinatorService.forceCompleteStalledTask(eq(UUID), anyString())).thenReturn(true);
        allQueuesEmpty();

        assertEquals(1, watchdog.sweep());
        verify(coordinatorService)
            .forceCompleteStalledTask(eq(UUID), anyString());
    }

    @Test
    void healthyBatch_isLeftAlone() {
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(false);
        allQueuesEmpty();

        assertEquals(0, watchdog.sweep());
        verify(coordinatorService, never()).forceCompleteStalledTask(anyString(), anyString());
    }

    @Test
    void pendingBrokerWork_isPassedThroughSoInFlightBatchesAreNotCutShort() {
        queueWithMessages(RabbitConfig.EMOJI_QUEUE, 3);
        queueWithMessages(RabbitConfig.IMAGE_QUEUE, 1);
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(false);

        watchdog.sweep();

        // The summed depth across every media queue must reach the classifier.
        verify(coordinatorService).isStalled(UUID, 4L, GRACE_MILLIS);
    }

    @Test
    void retryHoldQueueDepthAlsoCounts() {
        queueWithMessages(RabbitConfig.EMOJI_RETRY_HOLD_QUEUE, 2);
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(false);

        watchdog.sweep();

        verify(coordinatorService).isStalled(UUID, 2L, GRACE_MILLIS);
    }

    @Test
    void unreachableQueueDoesNotDisableTheSweep() {
        // A queue-info failure must not abort the sweep: it only makes the "nothing in flight"
        // signal less certain, and the heartbeat check still guards against a premature close.
        when(amqpAdmin.getQueueInfo(RabbitConfig.IMAGE_QUEUE))
            .thenThrow(new RuntimeException("broker hiccup"));
        queueWithMessages(RabbitConfig.EMOJI_QUEUE, 0);
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(true);
        when(coordinatorService.remainingTasks(UUID)).thenReturn(2);
        when(coordinatorService.forceCompleteStalledTask(eq(UUID), anyString())).thenReturn(true);

        assertEquals(1, watchdog.sweep());
    }

    @Test
    void enumerationFailureIsSwallowed() {
        when(coordinatorService.findUnfinishedTaskIds())
            .thenThrow(new RuntimeException("redis down"));

        assertEquals(0, watchdog.sweep());
    }

    @Test
    void perBatchFailureDoesNotStopTheSweep() {
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of("a", UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq("a"), anyLong(), anyLong()))
            .thenThrow(new RuntimeException("boom"));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(true);
        when(coordinatorService.remainingTasks(UUID)).thenReturn(1);
        when(coordinatorService.forceCompleteStalledTask(eq(UUID), anyString())).thenReturn(true);
        allQueuesEmpty();

        assertEquals(1, watchdog.sweep());
    }

    @Test
    void forceCompleteFailure_isNotCountedAsRescued() {
        when(coordinatorService.findUnfinishedTaskIds()).thenReturn(Set.of(UUID));
        when(coordinatorService.stallGracePeriod())
            .thenReturn(java.time.Duration.ofMillis(GRACE_MILLIS));
        when(coordinatorService.isStalled(eq(UUID), anyLong(), anyLong())).thenReturn(true);
        when(coordinatorService.remainingTasks(UUID)).thenReturn(1);
        when(coordinatorService.forceCompleteStalledTask(eq(UUID), anyString())).thenReturn(false);
        allQueuesEmpty();

        assertEquals(0, watchdog.sweep());
    }

    @Test
    void defaultGracePeriodIsConfiguredOnTaskConfig() {
        // Guard the default so a misconfigured property cannot make the watchdog fire instantly and
        // truncate healthy long-running batches.
        TaskConfig config = new TaskConfig();
        assertTrue(config.getStallGracePeriod().toMinutes() >= 5,
            "grace period must comfortably exceed a single slow media item");
        assertTrue(config.getWatchdogInterval().toMinutes() >= 1);
        assertEquals(java.time.Duration.ofDays(1), config.getRedisTtl());
    }
}