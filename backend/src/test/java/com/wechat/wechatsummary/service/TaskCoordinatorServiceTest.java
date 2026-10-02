package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.config.TaskConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.dto.TaskStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskCoordinatorServiceTest {

    private static final String UUID = "test-uuid";
    private static final String USER_ID = "user-123";

    @TempDir
    Path tmp;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private MessageProcessorService messageProcessorService;

    @Mock
    private StoragePaths storagePaths;

    @Mock
    private TaskConfig taskConfig;

    private TaskCoordinatorService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TaskCoordinatorService(redisTemplate, messageProcessorService,
            storagePaths, taskConfig);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void noMdExists() {
        when(storagePaths.processedMarkdown(USER_ID, UUID))
            .thenReturn(tmp.resolve("nonexistent.md"));
    }

    private void mdExists() throws Exception {
        Path mdFile = tmp.resolve(UUID + "_processed.md");
        java.nio.file.Files.writeString(mdFile, "# summary");
        when(storagePaths.processedMarkdown(USER_ID, UUID)).thenReturn(mdFile);
    }

    private void redisTotal(String value) {
        when(valueOps.get("task:total:" + UUID)).thenReturn(value);
    }

    private void redisCounter(String value) {
        when(valueOps.get("task:counter:" + UUID)).thenReturn(value);
    }

    private void redisTotalMissing() {
        when(valueOps.get("task:total:" + UUID)).thenReturn(null);
    }

    private void redisCounterMissing() {
        when(valueOps.get("task:counter:" + UUID)).thenReturn(null);
    }

    private void abortKeyExists() {
        when(redisTemplate.hasKey("task:aborted:" + UUID)).thenReturn(true);
    }

    private void abortKeyMissing() {
        when(redisTemplate.hasKey("task:aborted:" + UUID)).thenReturn(false);
    }

    private void counterKeyExists() {
        when(redisTemplate.hasKey("task:counter:" + UUID)).thenReturn(true);
    }

    private void counterKeyMissing() {
        when(redisTemplate.hasKey("task:counter:" + UUID)).thenReturn(false);
    }

    private void totalKeyExists() {
        when(redisTemplate.hasKey("task:total:" + UUID)).thenReturn(true);
    }

    private void totalKeyMissing() {
        when(redisTemplate.hasKey("task:total:" + UUID)).thenReturn(false);
    }

    // ── Idle ──────────────────────────────────────────────────────────────

    @Test
    void idle_noMd_noRedis_noThreads() {
        noMdExists();
        abortKeyMissing();
        redisTotalMissing();
        redisCounterMissing();
        counterKeyMissing();
        totalKeyMissing();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.IDLING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    // ── Fresh task ────────────────────────────────────────────────────────

    @Test
    void freshTask_totalExists_counterEqualsTotal_noThreads() {
        noMdExists();
        abortKeyMissing();
        redisTotal("100");
        redisCounter("100");
        counterKeyExists();
        totalKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void freshTask_totalExists_counterMissing_noThreads() {
        noMdExists();
        abortKeyMissing();
        redisTotal("100");
        redisCounterMissing();
        counterKeyMissing();
        totalKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    // ── Mid-processing ────────────────────────────────────────────────────

    @Test
    void midProcessing_total100_remaining40_withActiveThread() {
        noMdExists();
        abortKeyMissing();
        redisTotal("100");
        redisCounter("40");
        counterKeyExists();
        totalKeyExists();

        Thread worker = new Thread(() -> {});
        worker.start();
        service.registerThread(UUID, worker);

        try {
            TaskProgress p = service.getTaskProgress(UUID, USER_ID);
            assertEquals(TaskStatus.RUNNING, p.getStatus());
            assertEquals(60.0, p.getProgressPercentage(), 0.001);
        } finally {
            worker.interrupt();
        }
    }

    // ── Counter exists but threads finished ────────────────────────────────

    @Test
    void counterExists_noThreads_remainingZero() {
        noMdExists();
        abortKeyMissing();
        redisTotal("100");
        redisCounter("0");
        counterKeyExists();
        totalKeyMissing();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    // ── Completed (MD is source of truth) ──────────────────────────────────

    @Test
    void completed_mdExists_redisMayBeStale() throws Exception {
        mdExists();
        // Redis contains stale/incomplete/zero counters
        redisTotal("50");
        redisCounter("30");
        abortKeyMissing();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.COMPLETED, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void completed_mdExists_redisRemainingNonzero() throws Exception {
        mdExists();
        redisTotal("100");
        redisCounter("50");
        abortKeyMissing();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.COMPLETED, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    // ── Paused ────────────────────────────────────────────────────────────

    @Test
    void paused_withCounts() {
        noMdExists();
        abortKeyExists();
        redisTotal("100");
        redisCounter("40");
        counterKeyMissing();
        totalKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.PAUSED, p.getStatus());
        assertEquals(60.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void paused_noCountInformation() {
        noMdExists();
        abortKeyExists();
        redisTotalMissing();
        redisCounterMissing();
        counterKeyMissing();
        totalKeyMissing();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.PAUSED, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    // ── Empty counts (0/0) never means 100% ──────────────────────────────

    @Test
    void emptyCounts_runningNot100() {
        noMdExists();
        abortKeyMissing();
        redisTotal("0");
        redisCounter("0");
        counterKeyExists();
        totalKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    @Test
    void emptyCounts_pausedNot100() {
        noMdExists();
        abortKeyExists();
        redisTotal("0");
        redisCounter("0");

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.PAUSED, p.getStatus());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    // ── Invalid Redis values ──────────────────────────────────────────────

    @Test
    void invalidRedisValues_doNotThrow() {
        noMdExists();
        abortKeyMissing();
        redisTotal("abc");
        redisCounter("xyz");
        counterKeyExists();
        totalKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.RUNNING, p.getStatus());
        assertEquals(0, p.getTotalTasks());
        assertEquals(0, p.getRemainingTasks());
        assertEquals(0.0, p.getProgressPercentage(), 0.001);
    }

    // ── Stale Redis after completion ──────────────────────────────────────

    @Test
    void staleRedis_afterCompletion_stillCompleted() throws Exception {
        mdExists();
        redisTotal("999");
        redisCounter("-10");
        abortKeyExists(); // abort flag might still exist
        counterKeyExists();

        TaskProgress p = service.getTaskProgress(UUID, USER_ID);
        assertEquals(TaskStatus.COMPLETED, p.getStatus());
        assertEquals(100.0, p.getProgressPercentage(), 0.001);
    }

    // ── Stall watchdog ────────────────────────────────────────────────────
    //
    // A lost media message leaves the counter above zero forever, so the UI would report the run as
    // stuck indefinitely. These guard the predicate that lets the watchdog close such a batch out
    // while leaving anything genuinely in flight alone.

    private static final long GRACE = 20 * 60 * 1000L;

    @Test
    void stalled_whenNoThreads_noBrokerWork_staleHeartbeat() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID))
            .thenReturn(String.valueOf(System.currentTimeMillis() - (GRACE + 60_000)));

        assertTrue(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void notStalled_whenHeartbeatIsFresh() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID))
            .thenReturn(String.valueOf(System.currentTimeMillis()));

        assertFalse(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void notStalled_whenBrokerStillHasWork() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID))
            .thenReturn(String.valueOf(System.currentTimeMillis() - (GRACE + 60_000)));

        // Messages still queued means the batch is legitimately in flight, however old the
        // heartbeat looks - a slow media item must never be mistaken for a lost one.
        assertFalse(service.isStalled(UUID, 1, GRACE));
    }

    @Test
    void notStalled_whenAWorkerThreadIsActive() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID))
            .thenReturn(String.valueOf(System.currentTimeMillis() - (GRACE + 60_000)));

        Thread worker = new Thread(() -> {
        });
        worker.start();
        service.registerThread(UUID, worker);
        try {
            assertFalse(service.isStalled(UUID, 0, GRACE));
        } finally {
            worker.interrupt();
        }
    }

    @Test
    void notStalled_whenAborted() {
        // An abort is a deliberate user action, not a fault: the watchdog must never resurrect it.
        abortKeyExists();

        assertFalse(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void stalled_whenHeartbeatUnparseable() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID)).thenReturn("not-a-number");

        assertTrue(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void stalled_whenHeartbeatMissingAndCounterIsOld() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID)).thenReturn(null);
        when(redisTemplate.getExpire("task:counter:" + UUID)).thenReturn(5L);

        assertTrue(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void notStalled_whenHeartbeatMissingAndCounterIsFresh() {
        abortKeyMissing();
        when(valueOps.get("task:heartbeat:" + UUID)).thenReturn(null);
        when(redisTemplate.getExpire("task:counter:" + UUID)).thenReturn(GRACE / 1000);

        assertFalse(service.isStalled(UUID, 0, GRACE));
    }

    @Test
    void forceComplete_clearsCountersAndCompilesMarkdown() {
        when(valueOps.get("task:user:" + UUID)).thenReturn(USER_ID);

        assertTrue(service.forceCompleteStalledTask(UUID, "1 sub-task(s) never reported completion"));

        verify(redisTemplate).delete("task:counter:" + UUID);
        verify(redisTemplate).delete("task:total:" + UUID);
        verify(redisTemplate).delete("task:user:" + UUID);
        verify(redisTemplate).delete("task:heartbeat:" + UUID);
        // The document is still produced, from whatever media finished - a partial result the user
        // can act on beats an endless spinner.
        verify(messageProcessorService).processJsonAndSave(USER_ID, UUID);
    }

    @Test
    void forceComplete_abortedBatchIsLeftAlone() {
        abortKeyExists();

        assertFalse(service.forceCompleteStalledTask(UUID, "stalled"));
        verify(messageProcessorService, never()).processJsonAndSave(anyString(), anyString());
    }

    @Test
    void forceComplete_withoutUserId_clearsCounterAndDoesNotCompile() {
        when(valueOps.get("task:user:" + UUID)).thenReturn(null);

        assertTrue(service.forceCompleteStalledTask(UUID, "stalled"));
        verify(redisTemplate).delete("task:counter:" + UUID);
        verify(messageProcessorService, never()).processJsonAndSave(anyString(), anyString());
    }

    @Test
    void forceComplete_compileFailure_isReportedNotThrown() {
        when(valueOps.get("task:user:" + UUID)).thenReturn(USER_ID);
        org.mockito.Mockito.doThrow(new RuntimeException("compile failed"))
            .when(messageProcessorService).processJsonAndSave(anyString(), anyString());

        assertFalse(service.forceCompleteStalledTask(UUID, "stalled"));
    }

    @Test
    void findUnfinishedTaskIds_extractsUuidsFromCounterKeys() {
        when(redisTemplate.keys("task:counter:*"))
            .thenReturn(java.util.Set.of("task:counter:uuid-a", "task:counter:uuid-b"));

        assertEquals(java.util.Set.of("uuid-a", "uuid-b"), service.findUnfinishedTaskIds());
    }

    @Test
    void findUnfinishedTaskIds_handlesEmptyAndNull() {
        when(redisTemplate.keys("task:counter:*")).thenReturn(java.util.Set.of());
        assertTrue(service.findUnfinishedTaskIds().isEmpty());

        when(redisTemplate.keys("task:counter:*")).thenReturn(null);
        assertTrue(service.findUnfinishedTaskIds().isEmpty());
    }
}
