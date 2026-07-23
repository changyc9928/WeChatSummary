package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import java.io.File;
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
public class TaskTaskCoordinatorService {

    private static final String COUNTER_PREFIX = "task:counter:";
    private static final String TOTAL_PREFIX = "task:total:";
    private static final String ABORTED_PREFIX = "task:aborted:";

    // Thread-safe map tracking active JVM threads for each UUID batch
    private final Map<String, Set<Thread>> activeThreadsMap = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;
    private final StorageConfig storageConfig;

    public void initTaskContext(String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        // Clear any previous abort/paused state if re-initialized
        redisTemplate.delete(ABORTED_PREFIX + uuid);

        if (totalTasks <= 0) {
            log.info(
                "No media processing tasks required for batch UUID: [{}]. Bypassing queue wait; compiling transcript summary documents immediately.",
                uuid);
            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            return;
        }

        redisTemplate.opsForValue()
            .set(COUNTER_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);
        redisTemplate.opsForValue()
            .set(TOTAL_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);

        log.info(
            "Distributed transaction progress initialized for batch UUID: [{}] tracking total tasks: {}",
            uuid, totalTasks);
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
        redisTemplate.opsForValue().set(ABORTED_PREFIX + uuid, "true", 1, TimeUnit.DAYS);

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

    public void completeTask(String uuid, Thread thread, String inputJsonPath,
        String outputFilePath) {
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

            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);

            redisTemplate.delete(counterKey);
            redisTemplate.delete(TOTAL_PREFIX + uuid);
            activeThreadsMap.remove(uuid);

            log.info("Distributed progress counter cleaned up for identity: [{}]", uuid);
        }
    }

    /**
     * Dynamically determines task progress based on pause flag, artifact presence, and live thread
     * status.
     */
    public TaskProgress getTaskProgress(String uuid) {
        // 1. Check if the task has been explicitly aborted/paused -> PAUSED
        if (Boolean.TRUE.equals(redisTemplate.hasKey(ABORTED_PREFIX + uuid))) {
            return new TaskProgress("PAUSED", 0, 0);
        }

        // 2. Check if {uuid}_processed.md exists on disk -> COMPLETED
        String expectedOutputPath = storageConfig.getUploadDir()
            .resolve("outputs")
            .resolve(uuid + "_processed.md")
            .toAbsolutePath()
            .toString();

        if (new File(expectedOutputPath).exists()) {
            return new TaskProgress("COMPLETED", 0, 0);
        }

        // Fetch counts for payload reporting if still in progress
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
        String remainingStr = redisTemplate.opsForValue().get(COUNTER_PREFIX + uuid);
        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
        int remaining = remainingStr != null ? Integer.parseInt(remainingStr) : 0;

        // 3. Check if active threads exist for this UUID -> RUNNING
        Set<Thread> activeThreads = activeThreadsMap.get(uuid);
        if (activeThreads != null && !activeThreads.isEmpty()) {
            return new TaskProgress("RUNNING", total, remaining);
        }

        // 4. Otherwise -> IDLING
        return new TaskProgress("IDLING", total, remaining);
    }
}