package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import java.io.File;
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
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;
    private final StorageConfig storageConfig;

    public void initTaskContext(String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        if (totalTasks <= 0) {
            log.info(
                "No media processing tasks required for batch UUID: [{}]. Bypassing queue wait; compiling transcript summary documents immediately.",
                uuid);
            redisTemplate.opsForValue()
                .set(STATUS_PREFIX + uuid, STATUS_COMPLETED, 1, TimeUnit.DAYS);
            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            return;
        }

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
     * Pauses the given task batch execution. Ongoing background worker items will still decrement
     * the counter if they finish, but the orchestration pipeline will block moving to the
     * compilation phase.
     */
    public boolean pauseTask(String uuid) {
        String statusKey = STATUS_PREFIX + uuid;
        String currentStatus = redisTemplate.opsForValue().get(statusKey);

        if (currentStatus == null) {
            log.warn("Cannot pause. Task context not found for UUID: [{}]", uuid);
            return false;
        }

        if (STATUS_COMPLETED.equalsIgnoreCase(currentStatus)) {
            log.warn("Cannot pause. Task is already COMPLETED for UUID: [{}]", uuid);
            return false;
        }

        redisTemplate.opsForValue().set(statusKey, STATUS_PAUSED, 1, TimeUnit.DAYS);
        log.info("Task transaction batch UUID: [{}] has been successfully PAUSED.", uuid);
        return true;
    }

    /**
     * Resets the progress tracker counter back to its total capacity and sets status to PROCESSING.
     * Note: This method handles resetting the state tracker. Your actual background job
     * processors/workers will need to listen or poll to restart their physical operations.
     */
    public boolean startOverTask(String uuid, String inputJsonPath, String outputFilePath) {
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);

        if (totalStr == null) {
            log.warn(
                "Cannot start over. Initial total count tracking missing or expired for UUID: [{}]",
                uuid);
            return false;
        }

        // Reset the down-counter to original full allocation capacity
        redisTemplate.opsForValue().set(COUNTER_PREFIX + uuid, totalStr, 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(STATUS_PREFIX + uuid, STATUS_PROCESSING, 1, TimeUnit.DAYS);

        // Re-verify or restore configurations strings just in case they were cleaned up previously
        redisTemplate.opsForValue().set(INPUT_PATH_PREFIX + uuid, inputJsonPath, 1, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(FILE_PATH_PREFIX + uuid, outputFilePath, 1, TimeUnit.DAYS);

        log.info(
            "Task transaction batch UUID: [{}] has been reset to START OVER. Counter re-initialized to: {}",
            uuid, totalStr);
        return true;
    }

    public void completeTask(String uuid) {
        String statusKey = STATUS_PREFIX + uuid;
        String currentStatus = redisTemplate.opsForValue().get(statusKey);

        // Intercept action if system state is explicitly set to PAUSED
        if (STATUS_PAUSED.equalsIgnoreCase(currentStatus)) {
            log.info(
                "Task completion reported for UUID: [{}] but skipped compiling phase because execution is PAUSED.",
                uuid);
            // We still decrement the counter so background threads clean up nicely, but we return early.
            redisTemplate.opsForValue().decrement(COUNTER_PREFIX + uuid);
            return;
        }

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

        // Double check status right before final compilation to avoid racing a pause trigger
        currentStatus = redisTemplate.opsForValue().get(statusKey);
        if (STATUS_PAUSED.equalsIgnoreCase(currentStatus)) {
            log.info(
                "Race condition defense: Batch processing reached zero for UUID: [{}], but state shifted to PAUSED. Suspending final save phase.",
                uuid);
            return;
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

            redisTemplate.opsForValue()
                .set(STATUS_PREFIX + uuid, STATUS_COMPLETED, 1, TimeUnit.DAYS);

            redisTemplate.delete(counterKey);
            redisTemplate.delete(INPUT_PATH_PREFIX + uuid);
            redisTemplate.delete(FILE_PATH_PREFIX + uuid);
            log.info(
                "Distributed synchronization cache footprint cleaned up for pipeline identity: [{}]",
                uuid);
        }
    }

    public boolean isPreprocessFinished(String uuid) {
        String status = redisTemplate.opsForValue().get(STATUS_PREFIX + uuid);

        if (status != null) {
            return STATUS_COMPLETED.equalsIgnoreCase(status);
        }

        log.warn(
            "Redis status check returned null for UUID: [{}]. Executing disk fallback strategy.",
            uuid);
        String outputFilePath = redisTemplate.opsForValue().get(FILE_PATH_PREFIX + uuid);

        if (outputFilePath == null) {
            outputFilePath = storageConfig.getUploadDir()
                .resolve("outputs")
                .resolve(uuid + "_processed.md")
                .toAbsolutePath()
                .toString();
        }

        boolean fileExists = new File(outputFilePath).exists();
        if (fileExists) {
            log.info(
                "Fallback match successful: Output artifact found on disk for UUID: [{}]. Treating as completed.",
                uuid);
            return true;
        }

        log.error(
            "Fallback match failed: No output file found at [{}] for UUID: [{}]. Task was likely lost due to crash or reboot.",
            outputFilePath, uuid);
        return false;
    }

    public TaskProgress getTaskProgress(String uuid) {
        String status = redisTemplate.opsForValue().get(STATUS_PREFIX + uuid);

        if (status == null) {
            return new TaskProgress("NOT_FOUND", 0, 0);
        }

        // Return status safely whether it is COMPLETED, PROCESSING, or PAUSED
        String totalStr = redisTemplate.opsForValue().get(TOTAL_PREFIX + uuid);
        String remainingStr = redisTemplate.opsForValue().get(COUNTER_PREFIX + uuid);

        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
        int remaining = remainingStr != null ? Integer.parseInt(remainingStr) : 0;

        return new TaskProgress(status, total, remaining);
    }
}