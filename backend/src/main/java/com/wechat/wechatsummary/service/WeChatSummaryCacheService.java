package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ChatSummaryStatus;
import com.wechat.wechatsummary.entity.ChatSummaryTask;
import com.wechat.wechatsummary.entity.EmojiSummaryEntity;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.entity.VideoSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ChatSummaryTaskRepository;
import com.wechat.wechatsummary.repository.EmojiSummaryRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import com.wechat.wechatsummary.repository.VideoSummaryRepository;
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
    private final VideoSummaryRepository videoSummaryRepository;
    private final EmojiSummaryRepository emojiSummaryRepository;
    private final ChatSummaryTaskRepository taskRepository;
    private final StringRedisTemplate redisTemplate;
    private final CacheManager cacheManager;
    private final StoragePaths storagePaths;

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
    public Optional<String> getImageSummary(String hash) {
        log.info(
                "Cache miss for image_summary signature target [{}]. Querying relational persistence layers...",
                hash);
        return imageSummaryRepository.findByImageHash(hash).map(ImageSummaryEntity::getSummary);
    }

    /**
     * Resolves an image summary by looking up the stored file path containing the given md5
     * fragment. This is the primary mechanism for referenced images, whose lookup key is an md5
     * (embedded in both the reference XML and the on-disk file name) rather than a path-derived
     * hash.
     */
    public Optional<String> getImageSummaryByMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return Optional.empty();
        }
        return imageSummaryRepository.findByFilePathContaining(md5)
                .stream()
                .findFirst()
                .map(ImageSummaryEntity::getSummary);
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
     * Persists an image summary entity to DB and invalidates image summary list
     * caches.
     */
    @CacheEvict(cacheNames = "image_summary_list", allEntries = true)
    public ImageSummaryEntity saveImageSummary(ImageSummaryEntity entity) {
        log.info("Persisting image summary record for hash: [{}]", entity.getImageHash());
        ImageSummaryEntity saved = imageSummaryRepository.save(entity);
        evictImageSummary(entity.getImageHash());
        return saved;
    }

    public void deleteSessionImageSummaries(String uuid) {
        List<ImageSummaryEntity> saved = imageSummaryRepository.findByFilePathContainingUuid(uuid);
        for (ImageSummaryEntity imageSummaryEntity : saved) {
            cacheManager.getCache("image_summary").evict(imageSummaryEntity.getImageHash());
            imageSummaryRepository.deleteById(imageSummaryEntity.getId());
        }
        cacheManager.getCache("image_summary_list").evict(uuid);
    }

    /**
     * Deletes a single image summary record by ID (hash) and evicts relevant
     * caches.
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
     * Batch deletes image summary records by IDs (hashes) and evicts relevant
     * caches.
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
    // 1.5 EMOJI (ANIMATED STICKER) SUMMARY LAYER (DB + Cache Abstraction)
    // =========================================================================

    /**
     * Checks if an emoji summary record exists by hash.
     */
    public Optional<EmojiSummaryEntity> findEmojiSummaryByHash(String hash) {
        return emojiSummaryRepository.findByEmojiHash(hash);
    }

    /**
     * Retrieves cached emoji summary string or loads from DB.
     */
    public Optional<String> getEmojiSummary(String hash) {
        log.info(
                "Cache miss for emoji_summary signature target [{}]. Querying relational persistence layers...",
                hash);
        return emojiSummaryRepository.findByEmojiHash(hash).map(EmojiSummaryEntity::getSummary);
    }

    /**
     * Caches emoji summary entity records scoped by target session/chat UUID.
     */
    @Cacheable(cacheNames = "emoji_summary_list", key = "#uuid", sync = true)
    public List<EmojiSummaryEntity> getEmojiSummariesByUuid(String uuid) {
        log.info(
                "Cache miss for emoji_summary_list for UUID: [{}]. Querying relational persistence layer...",
                uuid);
        return emojiSummaryRepository.findByFilePathContainingUuid(uuid);
    }

    /**
     * Persists an emoji summary entity to DB and invalidates emoji summary list caches.
     */
    @CacheEvict(cacheNames = "emoji_summary_list", allEntries = true)
    public EmojiSummaryEntity saveEmojiSummary(EmojiSummaryEntity entity) {
        log.info("Persisting emoji summary record for hash: [{}]", entity.getEmojiHash());
        EmojiSummaryEntity saved = emojiSummaryRepository.save(entity);
        evictEmojiSummary(entity.getEmojiHash());
        return saved;
    }

    public void deleteSessionEmojiSummaries(String uuid) {
        List<EmojiSummaryEntity> saved = emojiSummaryRepository.findByFilePathContainingUuid(uuid);
        for (EmojiSummaryEntity emojiSummaryEntity : saved) {
            cacheManager.getCache("emoji_summary").evict(emojiSummaryEntity.getEmojiHash());
            emojiSummaryRepository.deleteById(emojiSummaryEntity.getId());
        }
        cacheManager.getCache("emoji_summary_list").evict(uuid);
    }

    /**
     * Deletes a single emoji summary record by ID (hash) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "emoji_summary_list", allEntries = true)
    public void deleteEmojiSummaryById(String id) {
        log.info("Request to delete emoji summary record for ID: [{}]", id);
        if (emojiSummaryRepository.existsById(id)) {
            emojiSummaryRepository.deleteById(id);
            evictEmojiSummary(id);
            log.info("Successfully deleted emoji summary record and evicted caches for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes emoji summary records by IDs (hashes) and evicts relevant caches.
     */
    @CacheEvict(cacheNames = "emoji_summary_list", allEntries = true)
    public void deleteEmojiSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] emoji summary records.", ids.size());
        for (String id : ids) {
            if (emojiSummaryRepository.existsById(id)) {
                emojiSummaryRepository.deleteById(id);
                evictEmojiSummary(id);
            }
        }
        log.info("Completed batch deletion and cache eviction for provided IDs.");
    }

    @CachePut(cacheNames = "emoji_summary", key = "#hash")
    public Optional<String> putEmojiSummary(String hash, String summary) {
        if (log.isDebugEnabled()) {
            log.debug("Explicitly updating emoji cache entry mapping for hash key: {}", hash);
        }
        return Optional.ofNullable(summary);
    }

    @CacheEvict(cacheNames = "emoji_summary", key = "#hash")
    public void evictEmojiSummary(String hash) {
        log.info("Evicting and invalidating emoji cache address segment mapping for key hash: [{}]",
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
     * Persists an audio summary entity to DB and invalidates audio summary list
     * caches.
     */
    @CacheEvict(cacheNames = "audio_summary_list", allEntries = true)
    public AudioSummary saveAudioSummary(AudioSummary entity) {
        log.info("Persisting audio summary record for hash: [{}]", entity.getFileHash());
        AudioSummary saved = audioSummaryRepository.save(entity);
        evictAudioSummary(entity.getFileHash());
        return saved;
    }

    public void deleteSessionAudioSummaries(String uuid) {
        List<AudioSummary> saved = audioSummaryRepository.findByFilePathContainingUuid(uuid);
        for (AudioSummary audioSummaryEntity : saved) {
            cacheManager.getCache("audio_summary").evict(audioSummaryEntity.getFileHash());
            audioSummaryRepository.deleteById(audioSummaryEntity.getId());
        }
        cacheManager.getCache("audio_summary_list").evict(uuid);
    }

    /**
     * Deletes a single audio summary record by ID (hash) and evicts relevant
     * caches.
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
     * Batch deletes audio summary records by IDs (hashes) and evicts relevant
     * caches.
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
     * Clears ONLY the summary text for a single audio record by ID, keeping the
     * transcript intact.
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
    // 2.5 VIDEO MEDIA SUMMARY CACHE (Spring Cache Driven)
    // =========================================================================

    /**
     * Checks if a video summary record exists by file hash.
     */
    public Optional<VideoSummary> findVideoSummaryByHash(String hash) {
        return videoSummaryRepository.findByFileHash(hash);
    }

    public Optional<String> getVideoSummary(String hash) {
        log.info(
                "Cache miss for video_summary signature target [{}]. Querying relational persistence layer...",
                hash);
        return videoSummaryRepository.findByFileHash(hash).map(VideoSummary::getSummary);
    }

    /**
     * Caches video summary entity records scoped by target session/chat UUID.
     */
    @Cacheable(cacheNames = "video_summary_list", key = "#uuid", sync = true)
    public List<VideoSummary> getVideoSummariesByUuid(String uuid) {
        log.info(
                "Cache miss for video_summary_list for UUID: [{}]. Querying relational persistence layer...",
                uuid);
        return videoSummaryRepository.findByFilePathContainingUuid(uuid);
    }

    /**
     * Persists a video summary entity to DB and invalidates video summary list
     * caches.
     */
    @CacheEvict(cacheNames = "video_summary_list", allEntries = true)
    public VideoSummary saveVideoSummary(VideoSummary entity) {
        log.info("Persisting video summary record for hash: [{}]", entity.getFileHash());
        VideoSummary saved = videoSummaryRepository.save(entity);
        evictVideoSummary(entity.getFileHash());
        return saved;
    }

    public void deleteSessionVideoSummaries(String uuid) {
        List<VideoSummary> saved = videoSummaryRepository.findByFilePathContainingUuid(uuid);
        for (VideoSummary videoSummaryEntity : saved) {
            cacheManager.getCache("video_summary").evict(videoSummaryEntity.getFileHash());
            videoSummaryRepository.deleteById(videoSummaryEntity.getId());
        }
        cacheManager.getCache("video_summary_list").evict(uuid);
    }

    /**
     * Deletes a single video summary record by ID (hash) and evicts relevant
     * caches.
     */
    @CacheEvict(cacheNames = "video_summary_list", allEntries = true)
    public void deleteVideoSummaryById(String id) {
        log.info("Request to delete video summary record for ID: [{}]", id);
        if (videoSummaryRepository.existsById(id)) {
            videoSummaryRepository.deleteById(id);
            evictVideoSummary(id);
            log.info("Successfully deleted video summary record and evicted caches for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes video summary records by IDs (hashes) and evicts relevant
     * caches.
     */
    @CacheEvict(cacheNames = "video_summary_list", allEntries = true)
    public void deleteVideoSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] video summary records.", ids.size());
        for (String id : ids) {
            if (videoSummaryRepository.existsById(id)) {
                videoSummaryRepository.deleteById(id);
                evictVideoSummary(id);
            }
        }
        log.info("Completed batch deletion and cache eviction for provided IDs.");
    }

    /**
     * Clears ONLY the summary text for a single video record by ID.
     */
    @CacheEvict(cacheNames = "video_summary_list", allEntries = true)
    public void clearVideoSummaryTextById(String id) {
        log.info("Request to clear video summary text for ID: [{}]", id);
        videoSummaryRepository.findById(id).ifPresent(entity -> {
            entity.setSummary(null);
            entity.setTranscript(null);
            videoSummaryRepository.save(entity);
            evictVideoSummary(id);
            log.info("Successfully cleared video summary text and evicted cache for ID: [{}]", id);
        });
    }

    /**
     * Batch clears ONLY the summary text for provided video record IDs.
     */
    @CacheEvict(cacheNames = "video_summary_list", allEntries = true)
    public void clearVideoSummaryTextsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch summary text clearing aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch clear [{}] video summary texts.", ids.size());
        for (String id : ids) {
            videoSummaryRepository.findById(id).ifPresent(entity -> {
                entity.setSummary(null);
                entity.setTranscript(null);
                videoSummaryRepository.save(entity);
                evictVideoSummary(id);
            });
        }
        log.info("Completed batch clearing of video summary texts.");
    }

    @CachePut(cacheNames = "video_summary", key = "#hash")
    public Optional<String> putVideoSummary(String hash, String summary) {
        if (log.isDebugEnabled()) {
            log.debug("Explicitly updating video cache entry mapping for hash key: {}", hash);
        }
        return Optional.ofNullable(summary);
    }

    @CacheEvict(cacheNames = "video_summary", key = "#hash")
    public void evictVideoSummary(String hash) {
        log.info(
                "Evicting and invalidating video data context cache mapping segment for key hash: [{}]",
                hash);
    }

    // =========================================================================
    // 3. CHAT ANALYSIS TASK CACHE (Pure Event-Driven Eviction)
    // =========================================================================

    @Cacheable(cacheNames = "chat_analysis", key = "#uuid.toString()", sync = true)
    public Optional<ChatSummaryTask> getCachedTask(UUID uuid) {
        log.info(
                "Cache miss for rolling chat task sequence. Extracting profile from DB for UUID: {}",
                uuid);
        return taskRepository.findById(uuid);
    }

    public ChatSummaryTask saveAndCacheTask(ChatSummaryTask task) {
        ChatSummaryTask savedTask = taskRepository.save(task);

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

    public void setTaskStatus(UUID uuid, ChatSummaryStatus status) {
        String key = STATUS_KEY_PREFIX + uuid.toString();
        redisTemplate.opsForValue().set(key, status.name());
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

    // =========================================================================
    // 5. SESSION-SCOPED BULK CLEANUP HELPERS
    // =========================================================================

    /**
     * Deletes the chat analysis task DB record and its associated Spring Cache + Redis state.
     * The task UUID is the same as the session UUID.
     */
    public void deleteChatAnalysisTask(UUID uuid) {
        log.info("Cleaning up chat analysis task state for UUID: {}", uuid);
        cacheManager.getCache("chat_analysis").evict(uuid.toString());
        clearProgress(uuid);
        taskRepository.deleteById(uuid);
    }

    /**
     * Deletes session output files (processed markdown, summary text, summary temp) from disk.
     * Idempotent — missing files are silently ignored.
     */
    public void deleteSessionOutputs(String userId, String uuid) {
        log.info("Cleaning up output files for user [{}] session [{}]", userId, uuid);
        try {
            java.nio.file.Files.deleteIfExists(storagePaths.processedMarkdown(userId, uuid));
        } catch (Exception e) {
            log.warn("Failed to delete processed markdown for session {}: {}", uuid, e.getMessage());
        }
        try {
            java.nio.file.Files.deleteIfExists(storagePaths.summaryTxt(userId, uuid));
        } catch (Exception e) {
            log.warn("Failed to delete summary text for session {}: {}", uuid, e.getMessage());
        }
        try {
            java.nio.file.Files.deleteIfExists(storagePaths.summaryTemp(userId, uuid));
        } catch (Exception e) {
            log.warn("Failed to delete summary temp for session {}: {}", uuid, e.getMessage());
        }
    }
}
