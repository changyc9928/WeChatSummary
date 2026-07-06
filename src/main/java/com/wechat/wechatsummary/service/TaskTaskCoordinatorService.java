package com.wechat.wechatsummary.service;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestration service acting as a thread-safe distributed lock and coordinator using Redis keys.
 * Tracks individual background media processing tasks (images, voice notes) belonging to a batch
 * transaction, decrementing down to zero before triggering the final conversation compilation
 * phase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskTaskCoordinatorService {

    private static final String COUNTER_PREFIX = "task:counter:";
    private static final String INPUT_PATH_PREFIX = "task:inputpath:";
    private static final String FILE_PATH_PREFIX = "task:filepath:";

    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;

    /**
     * Initializes a transaction cluster tracking context inside the distributed cache. If the batch
     * contains zero attachment parsing requirements, it fast-tracks straight to generating final
     * logs.
     *
     * @param uuid           unique transaction identifier representing the batch session
     * @param totalTasks     aggregate count of media files discovered that require tracking
     * @param inputJsonPath  absolute file system path to the source raw chat logs JSON database
     *                       export
     * @param outputFilePath target file system path mapping where the polished data results will
     *                       persist
     */
    public void initTaskContext(String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        if (totalTasks <= 0) {
            log.info(
                "No media processing tasks required for batch UUID: [{}]. Bypassing queue wait; compiling transcript summary documents immediately.",
                uuid);
            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            return;
        }

        // Cache parameters with a standard 1-day retention expiration safety net window
        redisTemplate.opsForValue()
            .set(COUNTER_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(INPUT_PATH_PREFIX + uuid, inputJsonPath, 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(FILE_PATH_PREFIX + uuid, outputFilePath, 1, TimeUnit.DAYS);

        log.info(
            "Distributed transaction synchronization initialized for batch UUID: [{}] tracking total child task records: {}",
            uuid, totalTasks);
    }

    /**
     * Atomically decrements the execution counter trace attached to a specific transaction session.
     * When the state transitions precisely to zero, it signals that all asynchronous background
     * tasks have successfully wrapped up, immediately pulling target system parameters to trigger
     * final content compiling steps.
     *
     * @param uuid unique transaction identifier representing the batch session that a processed
     *             asset belonged to
     */
    public void completeTask(String uuid) {
        String counterKey = COUNTER_PREFIX + uuid;
        Long remaining = redisTemplate.opsForValue().decrement(counterKey);

        if (remaining == null) {
            if (log.isDebugEnabled()) {
                log.debug(
                    "Redis counter decrement returned null mapping for lookup address reference key: {}",
                    counterKey);
            }
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug(
                "Task sub-component completed for transaction UUID: [{}]. Continuous remaining task records left inside thread group counter: {}",
                uuid, remaining);
        }

        if (remaining == 0) {
            log.info(
                "All concurrent media child task loops completed for transaction UUID: [{}]. Dispatching final synchronization compiling thread...",
                uuid);

            String inputJsonPath = redisTemplate.opsForValue().get(INPUT_PATH_PREFIX + uuid);
            String outputFilePath = redisTemplate.opsForValue().get(FILE_PATH_PREFIX + uuid);

            if (inputJsonPath != null && outputFilePath != null) {
                messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            } else {
                log.error(
                    "Fatal transaction mapping interruption. Missing data layer metadata properties for batch reference handle: [{}]. Input path: [{}], Target output path: [{}]",
                    uuid, inputJsonPath, outputFilePath);
            }

            // Clean up temporary distributed task context memory profiles from Redis cache
            redisTemplate.delete(counterKey);
            redisTemplate.delete(INPUT_PATH_PREFIX + uuid);
            redisTemplate.delete(FILE_PATH_PREFIX + uuid);
            log.info(
                "Distributed synchronization cache footprint wiped successfully for pipeline identity: [{}]",
                uuid);
        }
    }
}