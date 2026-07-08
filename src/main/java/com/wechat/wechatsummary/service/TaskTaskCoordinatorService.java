package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.dto.TaskProgress;
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
    private static final String TOTAL_PREFIX = "task:total:";
    private static final String STATUS_PREFIX = "task:status:";
    private static final String INPUT_PATH_PREFIX = "task:inputpath:";
    private static final String FILE_PATH_PREFIX = "task:filepath:";

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;

    /**
     * Initializes a transaction cluster tracking context inside the distributed cache. If the batch
     * contains zero attachment parsing requirements, it fast-tracks straight to generating final
     * logs.
     */
    public void initTaskContext(String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        if (totalTasks <= 0) {
            log.info(
                "No media processing tasks required for batch UUID: [{}]. Bypassing queue wait; compiling transcript summary documents immediately.",
                uuid);
            // Mark as completed immediately since there's no background work to wait for
            redisTemplate.opsForValue()
                .set(STATUS_PREFIX + uuid, STATUS_COMPLETED, 1, TimeUnit.DAYS);
            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            return;
        }


        // Cache parameters with a standard 1-day retention expiration safety net window
        redisTemplate.opsForValue()
            .set(COUNTER_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);
        redisTemplate.opsForValue()
            .set(TOTAL_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(STATUS_PREFIX + uuid, STATUS_PROCESSING, 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(INPUT_PATH_PREFIX + uuid, inputJsonPath, 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(FILE_PATH_PREFIX + uuid, outputFilePath, 1, TimeUnit.DAYS);

        log.info(
            "Distributed transaction synchronization initialized for batch UUID: [{}] tracking total child task records: {}",
            uuid, totalTasks);
    }

    /**
     * Atomically decrements the execution counter trace attached to a specific transaction
     * session.
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

            // Update status to COMPLETED
            redisTemplate.opsForValue()
                .set(STATUS_PREFIX + uuid, STATUS_COMPLETED, 1, TimeUnit.DAYS);

            // Clean up temporary distributed task context paths, but keep STATUS and TOTAL for querying (they will expire in 1 day anyway)
            redisTemplate.delete(counterKey);
            redisTemplate.delete(INPUT_PATH_PREFIX + uuid);
            redisTemplate.delete(FILE_PATH_PREFIX + uuid);
            log.info(
                "Distributed synchronization cache footprint cleaned up for pipeline identity: [{}]",
                uuid);
        }
    }

    /**
     * Checks if the preprocessing tasks for a specific transaction session have completed.
     *
     * @param uuid unique transaction identifier
     * @return true if finished or if the task does not exist (completed/cleaned up long ago), false
     * otherwise
     */
    public boolean isPreprocessFinished(String uuid) {
        String status = redisTemplate.opsForValue().get(STATUS_PREFIX + uuid);
        // If status is null, it means it either never existed or expired (treated as finished/inactive)
        return status == null || STATUS_COMPLETED.equalsIgnoreCase(status);
    }

    /**
     * Fetches the detailed progress of the given transaction batch.
     *
     * @param uuid unique transaction identifier
     * @return TaskProgress metrics object
     */
    public TaskProgress getTaskProgress(String uuid) {
        String status = redisTemplate.opsForValue().get(STATUS_PREFIX + uuid);

        if (status == null) {
            // Task either doesn't exist or expired completely
            return new TaskProgress("NOT_FOUND", 0, 0);
        }

        if (STATUS_COMPLETED.equalsIgnoreCase(status)) {
            String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
            int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
            return new TaskProgress(STATUS_COMPLETED, total, 0);
        }

        // Processing state
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
        String remainingStr = redisTemplate.opsForValue().get(COUNTER_PREFIX + uuid);

        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
        int remaining = remainingStr != null ? Integer.parseInt(remainingStr) : 0;

        return new TaskProgress(STATUS_PROCESSING, total, remaining);
    }
}