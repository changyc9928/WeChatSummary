package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redisTemplate;
    private final CacheManager cacheManager;

    // =========================================================================
    // 1. IMAGE SUMMARY LAYER (DB + Cache Abstraction)
    // =========================================================================

    /**
     * Checks if an image summary record exists by hash.
     */
    public Optional<ImageSummaryEntity> findImageSummaryByHash(String hash) {
        return imageSummaryRepository.findByImageHash(hash);
    }

    /**
     * Retrieves cached summary string or loads from DB.
     */
    @Cacheable(cacheNames = "image_summary", key = "#hash", sync = true)
    public Optional<String> getImageSummary(String hash) {
        log.info(
            "Cache miss for image_summary signature target [{}]. Querying relational persistence layers...",
            hash);
        return imageSummaryRepository.findByImageHash(hash).map(ImageSummaryEntity::getSummary);
    }

    /**
     * Caches image summary entity records scoped by target session/chat UUID.
     */
    @Cacheable(cacheNames = "image_summary_list", key = "#uuid", sync = true)
    public List<ImageSummaryEntity> getImageSummariesByUuid(String uuid) {
        log.info(
            "Cache miss for image_summary_list for UUID: [{}]. Querying relational persistence layer...",
            uuid);
        return imageSummaryRepository.findByFilePathContainingUuid(uuid);
    }

    /**
     * Persists an image summary entity to DB and invalidates image summary list caches.
     */
    @CacheEvict(cacheNames = "image_summary_list", allEntries = true)
    public ImageSummaryEntity saveImageSummary(ImageSummaryEntity entity) {
        log.info("Persisting image summary record for hash: [{}]", entity.getImageHash());
        ImageSummaryEntity saved = imageSummaryRepository.save(entity);
        evictImageSummary(entity.getImageHash());
        return saved;
    }

    /**
     * Deletes a single image summary record by ID (hash) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "image_summary_list", allEntries = true)
    public void deleteImageSummaryById(String id) {
        log.info("Request to delete image summary record for ID: [{}]", id);
        if (imageSummaryRepository.existsById(id)) {
            imageSummaryRepository.deleteById(id);
            evictImageSummary(id);
            log.info("Successfully deleted image summary record and evicted caches for ID: [{}]",
                id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes image summary records by IDs (hashes) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "image_summary_list", allEntries = true)
    public void deleteImageSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] image summary records.", ids.size());
        for (String id : ids) {
            if (imageSummaryRepository.existsById(id)) {
                imageSummaryRepository.deleteById(id);
                evictImageSummary(id);
            }
        }
        log.info("Completed batch deletion and cache eviction for provided IDs.");
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

    /**
     * Checks if an audio summary record exists by file hash.
     */
    public Optional<AudioSummary> findAudioSummaryByHash(String hash) {
        return audioSummaryRepository.findByFileHash(hash);
    }

    @Cacheable(cacheNames = "audio_summary", key = "#hash", sync = true)
    public Optional<String> getAudioSummary(String hash) {
        log.info(
            "Cache miss for audio_summary signature target [{}]. Falling back to underlying persistence tables...",
            hash);
        return audioSummaryRepository.findByFileHash(hash).map(AudioSummary::getSummary);
    }

    /**
     * Caches audio summary entity records scoped by target session/chat UUID.
     */
    @Cacheable(cacheNames = "audio_summary_list", key = "#uuid", sync = true)
    public List<AudioSummary> getAudioSummariesByUuid(String uuid) {
        log.info(
            "Cache miss for audio_summary_list for UUID: [{}]. Querying relational persistence layer...",
            uuid);
        return audioSummaryRepository.findByFilePathContainingUuid(uuid);
    }

    /**
     * Persists an audio summary entity to DB and invalidates audio summary list caches.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public AudioSummary saveAudioSummary(AudioSummary entity) {
        log.info("Persisting audio summary record for hash: [{}]", entity.getFileHash());
        AudioSummary saved = audioSummaryRepository.save(entity);
        evictAudioSummary(entity.getFileHash());
        return saved;
    }

    /**
     * Deletes a single audio summary record by ID (hash) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public void deleteAudioSummaryById(String id) {
        log.info("Request to delete audio summary record for ID: [{}]", id);
        if (audioSummaryRepository.existsById(id)) {
            audioSummaryRepository.deleteById(id);
            evictAudioSummary(id);
            log.info("Successfully deleted audio summary record and evicted caches for ID: [{}]",
                id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes audio summary records by IDs (hashes) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public void deleteAudioSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] audio summary records.", ids.size());
        for (String id : ids) {
            if (audioSummaryRepository.existsById(id)) {
                audioSummaryRepository.deleteById(id);
                evictAudioSummary(id);
            }
        }
        log.info("Completed batch deletion and cache eviction for provided IDs.");
    }

    /**
     * Clears ONLY the summary text for a single audio record by ID, keeping the transcript intact.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public void clearAudioSummaryTextById(String id) {
        log.info("Request to clear audio summary text for ID: [{}]", id);
        audioSummaryRepository.findById(id).ifPresent(entity -> {
            entity.setSummary(null);
            audioSummaryRepository.save(entity);
            evictAudioSummary(id);
            log.info("Successfully cleared audio summary text and evicted cache for ID: [{}]", id);
        });
    }

    /**
     * Batch clears ONLY the summary text for provided audio record IDs.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public void clearAudioSummaryTextsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch summary text clearing aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch clear [{}] audio summary texts.", ids.size());
        for (String id : ids) {
            audioSummaryRepository.findById(id).ifPresent(entity -> {
                entity.setSummary(null);
                audioSummaryRepository.save(entity);
                evictAudioSummary(id);
            });
        }
        log.info("Completed batch clearing of audio summary texts.");
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

    public ChatAnalysisTask saveAndCacheTask(ChatAnalysisTask task) {
        ChatAnalysisTask savedTask = taskRepository.save(task);

        if (task.getStatus() != null) {
            setTaskStatus(task.getId(), task.getStatus());
        }

        var cache = cacheManager.getCache("chat_analysis");
        if (cache != null) {
            cache.put(task.getId().toString(), savedTask);
        }

        return savedTask;
    }

    // =========================================================================
    // 4. GRANULAR CHAT ANALYSIS STATE CONTROLS (Manual Redis Operations)
    // =========================================================================

    public void updateTaskProgress(UUID uuid, int processedIndex, int totalChunks) {
        String key = PROGRESS_KEY_PREFIX + uuid.toString();
        Map<String, String> progressMap = new HashMap<>();
        progressMap.put("processedIndex", String.valueOf(processedIndex));
        progressMap.put("totalChunks", String.valueOf(totalChunks));

        redisTemplate.opsForHash().putAll(key, progressMap);
    }

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

    public void setTaskStatus(UUID uuid, String status) {
        String key = STATUS_KEY_PREFIX + uuid.toString();
        redisTemplate.opsForValue().set(key, status.toUpperCase());
    }

    public String getTaskStatus(UUID uuid) {
        String key = STATUS_KEY_PREFIX + uuid.toString();
        return redisTemplate.opsForValue().get(key);
    }

    public void clearProgress(UUID uuid) {
        log.info("Clearing real-time progress indicators out of Redis memory mappings for UUID: {}",
            uuid);
        redisTemplate.delete(STATUS_KEY_PREFIX + uuid.toString());
        redisTemplate.delete(PROGRESS_KEY_PREFIX + uuid.toString());
    }
}