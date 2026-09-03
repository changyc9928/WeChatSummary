package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.TaskConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestration service acting as a thread-safe distributed progress tracker using Redis keys,
 * maintaining active local JVM thread references per batch UUID with dynamic state checking and
 * abortion support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskCoordinatorService {

    private static final String COUNTER_PREFIX = "task:counter:";
    private static final String TOTAL_PREFIX = "task:total:";
    private static final String ABORTED_PREFIX = "task:aborted:";
    private static final String USER_KEY_PREFIX = "task:user:";

    // Thread-safe map tracking active JVM threads for each UUID batch
    private final Map<String, Set<Thread>> activeThreadsMap = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;
    private final StoragePaths storagePaths;
    private final TaskConfig taskConfig;

    public void initTaskContext(String userId, String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        redisTemplate.delete(ABORTED_PREFIX + uuid);

        if (totalTasks <= 0) {
            log.info(
                "No media processing tasks required for batch UUID: [{}]. Compiling summary immediately.",
                uuid);
            messageProcessorService.processJsonAndSave(userId, uuid);
            return;
        }

        // Store userId in Redis mapping for this uuid so completion can fetch it if needed, or pass it directly
        long redisTtl = taskConfig.getRedisTtl().toDays();
        redisTemplate.opsForValue().set(USER_KEY_PREFIX + uuid, userId, redisTtl, TimeUnit.DAYS);
        redisTemplate.opsForValue()
            .set(COUNTER_PREFIX + uuid, String.valueOf(totalTasks), redisTtl, TimeUnit.DAYS);
        redisTemplate.opsForValue()
            .set(TOTAL_PREFIX + uuid, String.valueOf(totalTasks), redisTtl, TimeUnit.DAYS);
    }

    /**
     * Registers a thread instance passed down by the caller under the specified UUID.
     *
     * @param uuid   The batch transaction identifier.
     * @param thread The active worker thread instance.
     */
    public void registerThread(String uuid, Thread thread) {
        if (thread == null) {
            return;
        }

        activeThreadsMap
            .computeIfAbsent(uuid, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(thread);

        log.info("Registered thread [{}] for batch UUID: [{}]", thread.getName(), uuid);
    }

    /**
     * Removes a previously registered worker thread once it has finished processing (successfully
     * or after a retry hand-off), so aborted/paused interrupts no longer target stale threads.
     *
     * @param uuid   The batch transaction identifier.
     * @param thread The finished worker thread instance.
     */
    public void unregisterThread(String uuid, Thread thread) {
        if (thread == null) {
            return;
        }
        Set<Thread> threads = activeThreadsMap.get(uuid);
        if (threads != null) {
            threads.remove(thread);
        }
    }

    /**
     * Instantly aborts all active worker threads, stores a flag in Redis, and purges active counter
     * data for the given UUID.
     *
     * @param uuid The batch transaction identifier to abort.
     * @return true if tasks were aborted or state marked as aborted.
     */
    public boolean abortTask(String uuid) {
        Set<Thread> threads = activeThreadsMap.remove(uuid);

        // Store abort flag in Redis to represent PAUSED status
        redisTemplate.opsForValue().set(ABORTED_PREFIX + uuid, "true", taskConfig.getRedisTtl()
            .toDays(), TimeUnit.DAYS);

        // Delete active counter keys in Redis
        redisTemplate.delete(COUNTER_PREFIX + uuid);
        redisTemplate.delete(TOTAL_PREFIX + uuid);

        if (threads != null && !threads.isEmpty()) {
            for (Thread thread : threads) {
                if (thread != null && thread.isAlive()) {
                    log.info("Interrupting active thread [{}] for aborted/paused UUID: [{}]",
                        thread.getName(), uuid);
                    thread.interrupt();
                }
            }
            log.info("Successfully aborted all {} task thread(s) for batch UUID: [{}]",
                threads.size(), uuid);
            return true;
        }

        log.warn(
            "Abort triggered, marked UUID [{}] as paused in Redis (no active threads were running).",
            uuid);
        return true;
    }

    /**
     * Checks whether the batch task for the given UUID was explicitly aborted.
     *
     * @param uuid The batch transaction identifier.
     * @return true if the abort flag key exists in Redis.
     */
    public boolean isAborted(String uuid) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ABORTED_PREFIX + uuid));
    }

    /**
     * Reports a finished sub-task for the given UUID and triggers the final markdown compilation
     * once all sub-tasks have completed.
     *
     * @param uuid   The batch transaction identifier.
     * @param thread The finished worker thread instance.
     */
    public void completeTask(String uuid, Thread thread) {
        // Guard check: do not process final compile if batch was aborted/paused
        if (Boolean.TRUE.equals(redisTemplate.hasKey(ABORTED_PREFIX + uuid))) {
            log.info(
                "Task completion reported for UUID: [{}] but ignored because transaction was PAUSED.",
                uuid);
            return;
        }

        // Remove the finished thread from memory
        Set<Thread> threads = activeThreadsMap.get(uuid);
        if (threads != null && thread != null) {
            threads.remove(thread);
            if (threads.isEmpty()) {
                activeThreadsMap.remove(uuid);
            }
        }

        String counterKey = COUNTER_PREFIX + uuid;
        Long remaining = redisTemplate.opsForValue().decrement(counterKey);

        if (remaining == null) {
            if (log.isDebugEnabled()) {
                log.debug("Redis counter decrement returned null for key: {}", counterKey);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug(
                "Task sub-component completed for transaction UUID: [{}]. Remaining tasks: {}",
                uuid, remaining);
        }

        if (remaining <= 0) {
            log.info(
                "All concurrent child tasks completed for transaction UUID: [{}]. Dispatching final processing...",
                uuid);

            String userId = redisTemplate.opsForValue().get(USER_KEY_PREFIX + uuid);
            if (userId != null) {
                messageProcessorService.processJsonAndSave(userId, uuid);
            } else {
                log.error("Failed to resolve userId from Redis for completed task UUID: [{}]",
                    uuid);
            }

            redisTemplate.delete(counterKey);
            redisTemplate.delete(TOTAL_PREFIX + uuid);
            redisTemplate.delete(USER_KEY_PREFIX + uuid);
            activeThreadsMap.remove(uuid);
        }
    }

    /**
     * Dynamically determines task progress. The processed Markdown file is the single source
     * of truth for COMPLETED. Redis state and active-thread state only describe incomplete
     * task progress.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>Processed MD exists → COMPLETED</li>
     *   <li>Explicitly aborted/paused → PAUSED</li>
     *   <li>Active threads → RUNNING</li>
     *   <li>Counter key exists → RUNNING</li>
     *   <li>Any Redis task context exists → RUNNING</li>
     *   <li>Otherwise → IDLING</li>
     * </ol>
     */
    public TaskProgress getTaskProgress(String uuid, String userId) {
        // 1. Processed Markdown exists → COMPLETED (authoritative, wins over all Redis state)
        if (Files.exists(storagePaths.processedMarkdown(userId, uuid))) {
            return TaskProgress.completed(0);
        }

        // Fetch counts defensively; missing/invalid values become 0
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
        String remainingStr = redisTemplate.opsForValue().get(COUNTER_PREFIX + uuid);
        int total = safeParse(totalStr);
        int remaining = safeParse(remainingStr);
        boolean counterKeyPresent = Boolean.TRUE.equals(redisTemplate.hasKey(COUNTER_PREFIX + uuid));
        boolean totalKeyPresent = Boolean.TRUE.equals(redisTemplate.hasKey(TOTAL_PREFIX + uuid));

        // 2. Explicitly aborted/paused → PAUSED
        if (Boolean.TRUE.equals(redisTemplate.hasKey(ABORTED_PREFIX + uuid))) {
            return TaskProgress.paused(total, remaining);
        }

        // 3. Active threads → RUNNING
        Set<Thread> activeThreads = activeThreadsMap.get(uuid);
        if (activeThreads != null && !activeThreads.isEmpty()) {
            return TaskProgress.running(total, remaining);
        }

        // 4. Counter key exists → RUNNING (covers window after threads finish but before
        //    final Markdown compilation completes; prevents UI flicker to IDLING)
        if (counterKeyPresent) {
            return TaskProgress.running(total, remaining);
        }

        // 5. Total key exists but counter not yet initialized → RUNNING, 0%
        //    This handles the fresh-task window between initTaskContext and the first
        //    consumer decrement. The counter has not been created yet, so remaining is
        //    meaningless; report 0% to avoid a premature 100% flash.
        if (totalKeyPresent) {
            return TaskProgress.running(total, total);
        }

        // 6. No context at all → IDLING
        return TaskProgress.idling();
    }

    /**
     * Parses a Redis string value to int, treating null, non-numeric, and negative values as 0.
     */
    private int safeParse(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Removes all TaskCoordinator Redis keys for the given UUID.
     * Used during session deletion to prevent orphaned task state.
     */
    public void cleanupTaskKeys(String uuid) {
        log.info("Cleaning up TaskCoordinator Redis keys for UUID: {}", uuid);
        redisTemplate.delete(COUNTER_PREFIX + uuid);
        redisTemplate.delete(TOTAL_PREFIX + uuid);
        redisTemplate.delete(ABORTED_PREFIX + uuid);
        redisTemplate.delete(USER_KEY_PREFIX + uuid);
    }
}