package com.wechat.wechatsummary.service;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskTaskCoordinatorService {

    private static final String COUNTER_PREFIX = "task:counter:";
    private static final String INPUT_PATH_PREFIX = "task:inputpath:"; // 新增：输入路径缓存
    private static final String FILE_PATH_PREFIX = "task:filepath:";
    private final StringRedisTemplate redisTemplate;
    private final MessageProcessorService messageProcessorService;

    /**
     * 初始化任务上下文，新增传入 inputJsonPath
     */
    public void initTaskContext(String uuid, int totalTasks, String inputJsonPath,
        String outputFilePath) {
        if (totalTasks <= 0) {
            log.info("No media tasks for UUID: {}, generate JSON immediately.", uuid);
            // 在 TaskTaskCoordinatorService 里的调用：
            messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            return;
        }

        redisTemplate.opsForValue()
            .set(COUNTER_PREFIX + uuid, String.valueOf(totalTasks), 1, TimeUnit.DAYS);
        redisTemplate.opsForValue()
            .set(INPUT_PATH_PREFIX + uuid, inputJsonPath, 1, TimeUnit.DAYS); // 缓存输入路径
        redisTemplate.opsForValue().set(FILE_PATH_PREFIX + uuid, outputFilePath, 1, TimeUnit.DAYS);
        log.info("Initialized task context for UUID: {} with {} tasks", uuid, totalTasks);
    }

    /**
     * 扣减并尝试触发归零
     */
    public void completeTask(String uuid) {
        String counterKey = COUNTER_PREFIX + uuid;
        Long remaining = redisTemplate.opsForValue().decrement(counterKey);

        if (remaining == null) {
            return;
        }

        if (remaining == 0) {
            log.info("All media tasks completed for UUID: {}. Triggering final JSON generation...",
                uuid);

            String inputJsonPath = redisTemplate.opsForValue().get(INPUT_PATH_PREFIX + uuid);
            String outputFilePath = redisTemplate.opsForValue().get(FILE_PATH_PREFIX + uuid);

            if (inputJsonPath != null && outputFilePath != null) {
                // 在 TaskTaskCoordinatorService 里的调用：
                messageProcessorService.processJsonAndSave(uuid, inputJsonPath, outputFilePath);
            } else {
                log.error("Lost task path context for UUID: {}, input or output path is null!",
                    uuid);
            }

            // 清理临时缓存
            redisTemplate.delete(counterKey);
            redisTemplate.delete(INPUT_PATH_PREFIX + uuid);
            redisTemplate.delete(FILE_PATH_PREFIX + uuid);
        }
    }
}