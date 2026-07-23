package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate; // Added for explicit operational access
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeChatSummaryCacheService {

    // Redis Key Constants
    private static final String STATUS_KEY_PREFIX = "chat_analysis:status:";
    private static final String PROGRESS_KEY_PREFIX = "chat_analysis:progress:";
    private final ImageSummaryRepository imageSummaryRepository;
    private final AudioSummaryRepository audioSummaryRepository;
    private final ChatAnalysisTaskRepository taskRepository;
    // Inject Redis template directly for fast progress tracking
    private final StringRedisTemplate redisTemplate;
    private final CacheManager cacheManager;

    // =========================================================================
    // 1. IMAGE SUMMARY CACHE (Spring Cache Driven)
    // =========================================================================

    @Cacheable(cacheNames = "image_summary", key = "#hash", sync = true)
    public Optional<String> getImageSummary(String hash) {
        log.info(
            "Cache miss for image_summary signature target [{}]. Querying relational persistence layers...",
            hash);
        Optional<ImageSummaryEntity> dbResult = imageSummaryRepository.findByImageHash(hash);
        return dbResult.map(ImageSummaryEntity::getSummary);
    }

    @CachePut(cacheNames = "image_summary", key = "#hash")
    public Optional<String> putImageSummary(String hash, String summary) {
        if (log.isDebugEnabled()) {
            log.debug("Explicitly updating image cache entry mapping for hash key: {}", hash);
        }
        return Optional.ofNullable(summary);
    }

    @CacheEvict(cacheNames = "image_summary", key = "#hash")
    public void evictImageSummary(String hash) {
        log.info("Evicting and invalidating image cache address segment mapping for key hash: [{}]",
            hash);
    }

    // =========================================================================
    // 2. AUDIO MEDIA SUMMARY CACHE (Spring Cache Driven)
    // =========================================================================

    @Cacheable(cacheNames = "audio_summary", key = "#hash", sync = true)
    public Optional<String> getAudioSummary(String hash) {
        log.info(
            "Cache miss for audio_summary signature target [{}]. Falling back to underlying persistence tables...",
            hash);
        return audioSummaryRepository.findByFileHash(hash)
            .map(AudioSummary::getSummary);
    }

    @CachePut(cacheNames = "audio_summary", key = "#hash")
    public Optional<String> putAudioSummary(String hash, String summary) {
        if (log.isDebugEnabled()) {
            log.debug("Explicitly updating audio cache entry mapping for hash key: {}", hash);
        }
        return Optional.ofNullable(summary);
    }

    @CacheEvict(cacheNames = "audio_summary", key = "#hash")
    public void evictAudioSummary(String hash) {
        log.info(
            "Evicting and invalidating audio data context cache mapping segment for key hash: [{}]",
            hash);
    }

    // =========================================================================
    // 3. CHAT ANALYSIS TASK CACHE (Pure Event-Driven Eviction)
    // =========================================================================

    @Cacheable(cacheNames = "chat_analysis", key = "#uuid.toString()", sync = true)
    public Optional<ChatAnalysisTask> getCachedTask(UUID uuid) {
        log.info(
            "Cache miss for rolling chat task sequence. Extracting profile from DB for UUID: {}",
            uuid);
        return taskRepository.findById(uuid);
    }

//    public ChatAnalysisTask saveAndEvictTask(ChatAnalysisTask task) {
//        log.info(
//            "Initializing task execution pipeline state. Clearing stale cache context parameters for UUID: {}",
//            task.getId());
//        evictTaskCache(task.getId());
//
//        // Also ensure Redis state status reflects database lifecycle pushes
//        if (task.getStatus() != null) {
//            setTaskStatus(task.getId(), task.getStatus());
//        }
//
//        return taskRepository.save(task);
//    }
//
//    @CacheEvict(cacheNames = "chat_analysis", key = "#uuid.toString()")
//    public void evictTaskCache(UUID uuid) {
//        log.info("Evicting task cache context parameters from memory space for task UUID: [{}]",
//            uuid);
//    }

    public ChatAnalysisTask saveAndCacheTask(ChatAnalysisTask task) {
        // 1. Persist/Merge updates to DB
        ChatAnalysisTask savedTask = taskRepository.save(task);

        // 2. Synchronize status indicators
        if (task.getStatus() != null) {
            setTaskStatus(task.getId(), task.getStatus());
        }

        // 3. Update Spring Cache programmatically with the RAW object
        var cache = cacheManager.getCache("chat_analysis");
        if (cache != null) {
            // FIX: Remove Optional.of(...) wrapper here
            cache.put(task.getId().toString(), savedTask);
        }

        return savedTask;
    }

    // =========================================================================
    // 4. GRANULAR CHAT ANALYSIS STATE CONTROLS (Manual Redis Operations)
    // =========================================================================

    /**
     * Updates the task execution loop statistics using a Redis Hash structure.
     */
    public void updateTaskProgress(UUID uuid, int processedIndex, int totalChunks) {
        String key = PROGRESS_KEY_PREFIX + uuid.toString();
        Map<String, String> progressMap = new HashMap<>();
        progressMap.put("processedIndex", String.valueOf(processedIndex));
        progressMap.put("totalChunks", String.valueOf(totalChunks));

        redisTemplate.opsForHash().putAll(key, progressMap);
    }

    /**
     * Safely reads progress tracking metric sets back out of our Redis Hash cache.
     */
    public Map<String, Integer> getProgressMetrics(UUID uuid) {
        String key = PROGRESS_KEY_PREFIX + uuid.toString();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        if (entries.isEmpty()) {
            return null;
        }

        Map<String, Integer> metrics = new HashMap<>();
        try {
            metrics.put("processedIndex", Integer.parseInt((String) entries.get("processedIndex")));
            metrics.put("totalChunks", Integer.parseInt((String) entries.get("totalChunks")));
        } catch (NumberFormatException e) {
            log.error(
                "Failed to accurately transform numerical progress metrics from cache keys for execution pipeline: {}",
                uuid, e);
            return null;
        }
        return metrics;
    }

    /**
     * Sets or updates explicit processing state indicators (e.g., RUNNING, PAUSED, SUCCESS).
     */
    public void setTaskStatus(UUID uuid, String status) {
        String key = STATUS_KEY_PREFIX + uuid.toString();
        redisTemplate.opsForValue().set(key, status.toUpperCase());
    }

    /**
     * Directly queries the processing status string identifier out of Redis.
     */
    public String getTaskStatus(UUID uuid) {
        String key = STATUS_KEY_PREFIX + uuid.toString();
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Fully purges ephemeral progress counters from Redis once jobs complete or get restarted.
     */
    public void clearProgress(UUID uuid) {
        log.info("Clearing real-time progress indicators out of Redis memory mappings for UUID: {}",
            uuid);
        redisTemplate.delete(STATUS_KEY_PREFIX + uuid.toString());
        redisTemplate.delete(PROGRESS_KEY_PREFIX + uuid.toString());
    }
}