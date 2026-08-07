package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.TaskConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.dto.TaskStatus;
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
     * Dynamically determines task progress based on pause flag, artifact presence, and live thread
     * status.
     */
    public TaskProgress getTaskProgress(String uuid, String userId) {
        // 1. Check if the task has been explicitly aborted/paused -> PAUSED
        if (Boolean.TRUE.equals(redisTemplate.hasKey(ABORTED_PREFIX + uuid))) {
            return new TaskProgress(TaskStatus.PAUSED, 0, 0);
        }

        // 2. Check if {uuid}_processed.md exists on disk -> COMPLETED
        if (Files.exists(storagePaths.processedMarkdown(userId, uuid))) {
            return new TaskProgress(TaskStatus.COMPLETED, 0, 0);
        }

        // Fetch counts for payload reporting if still in progress
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
        String remainingStr = redisTemplate.opsForValue().get(COUNTER_PREFIX + uuid);
        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
        int remaining = remainingStr != null ? Integer.parseInt(remainingStr) : 0;

        // 3. Check if active threads exist for this UUID -> RUNNING
        Set<Thread> activeThreads = activeThreadsMap.get(uuid);
        if (activeThreads != null && !activeThreads.isEmpty()) {
            return new TaskProgress(TaskStatus.RUNNING, total, remaining);
        }

        // 4. Otherwise -> IDLING
        return new TaskProgress(TaskStatus.IDLING, total, remaining);
    }
}