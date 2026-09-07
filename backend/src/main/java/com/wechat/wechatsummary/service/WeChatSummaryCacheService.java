package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.cache.CacheEvictionPublisher;
import com.wechat.wechatsummary.cache.CacheNames;
import com.wechat.wechatsummary.cache.SessionIdExtractor;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Black-box facade for media-summary persistence with cache coherence.
 *
 * <p>Callers use plain business operations ({@code save/delete/find/get}); they never see
 * RabbitMQ, delayed eviction, Redis, or cache internals. Coherence is deliberately simple:
 * <ol>
 *   <li>persist the business change in a transaction,</li>
 *   <li>after that transaction commits, publish a cache eviction message (a rollback therefore
 *   never produces a message),</li>
 *   <li>the message waits out the configured delay in a TTL/DLX holding queue,</li>
 *   <li>the consumer evicts the entry and acknowledges explicitly, so a failed eviction is
 *   redelivered by the broker.</li>
 * </ol>
 *
 * <p>Single-summary reads ({@code getImageSummary} etc.) are cache-aside over explicit
 * {@link CacheManager} operations — never Spring-cache annotations on self-invoked methods, which
 * proxy-based AOP cannot intercept. Session list reads use {@code @Cacheable}; both are
 * invalidated through the eviction pipeline (targeted by session UUID, never
 * {@code allEntries=true}).
 *
 * <p>Reads may observe a briefly stale entry between DB commit and eviction; publishing is
 * best-effort, so on broker failure an entry can stay stale until its TTL expires.
 */
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
    private final CacheEvictionPublisher evictionPublisher;
    private final StringRedisTemplate redisTemplate;
    private final CacheManager cacheManager;
    private final StoragePaths storagePaths;

    // =========================================================================
    // Internal helpers (cache-aside + eviction publishing; never exposed)
    // =========================================================================

    /**
     * Cache-aside read of one summary string. Only non-null summaries are cached (a null means
     * "row exists but has no summary yet", e.g. transcript-only audio, and must keep hitting the
     * DB until the summary is produced).
     */
    private Optional<String> readCachedSingle(
            String cacheName, String key, java.util.function.Supplier<Optional<String>> dbLoad) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            try {
                String hit = cache.get(key, String.class);
                if (hit != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Cache hit for {}:{}", cacheName, key);
                    }
                    return Optional.of(hit);
                }
            } catch (Exception e) {
                log.warn("Cache read failed for {}:{}; falling back to DB: {}",
                        cacheName, key, e.toString());
            }
        }
        log.info("Cache miss for {}:[{}]. Querying relational persistence layer...",
                cacheName, key);
        Optional<String> dbValue = dbLoad.get();
        if (dbValue.isPresent() && cache != null) {
            try {
                cache.put(key, dbValue.get());
            } catch (Exception e) {
                log.warn("Cache write failed for {}:{}: {}", cacheName, key, e.toString());
            }
        }
        return dbValue;
    }

    /**
     * Publishes eviction for one aggregate change: always the individual entry, plus the
     * session-scoped list entry (targeted when the session is known, whole-region clear as a
     * correctness fallback otherwise).
     *
     * <p>Publishing is deferred until the surrounding DB transaction commits (see
     * {@link #deferAfterCommit}), so a rolled-back write never emits an eviction.
     */
    private void publishChange(
            String singleCache, String listCache, String key, Optional<String> sessionId) {
        deferAfterCommit(() -> {
            evictionPublisher.evict(singleCache, key);
            if (sessionId.isPresent() && !sessionId.get().isBlank()) {
                evictionPublisher.evict(listCache, sessionId.get().trim());
            } else {
                log.warn("Session id unavailable for {}:{}; clearing list cache {} instead",
                        singleCache, key, listCache);
                evictionPublisher.clear(listCache);
            }
        });
    }

    private void publishChange(String singleCache, String listCache, String key, String filePath) {
        publishChange(singleCache, listCache, key,
                SessionIdExtractor.extractSessionId(filePath));
    }

    /**
     * Couples message production to the DB transaction: {@code publish} runs only in
     * {@code afterCommit}, so a rolled-back business write never produces an eviction message.
     * Without an active transaction (plain unit tests, future non-transactional callers) the
     * publish runs immediately.
     */
    private void deferAfterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }

    private static String keyOrId(String hash, String id) {
        if (hash != null && !hash.isBlank()) {
            return hash;
        }
        if (id != null && !id.isBlank()) {
            return id;
        }
        throw new IllegalArgumentException("Cannot derive cache key: hash and id are both blank");
    }

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
     * Returns the cached summary string, loading from DB on miss.
     */
    public Optional<String> getImageSummary(String hash) {
        return readCachedSingle(CacheNames.IMAGE_SUMMARY, hash,
                () -> imageSummaryRepository.findByImageHash(hash)
                        .map(ImageSummaryEntity::getSummary));
    }

    /**
     * Resolves an image summary by looking up the stored file path containing the given md5
     * fragment. This is the primary mechanism for referenced images, whose lookup key is an md5
     * (embedded in both the reference XML and the on-disk file name) rather than a path-derived
     * hash.
     *
     * <p>Intentionally not cached: the md5→row mapping is a derived DB search without a stable
     * cache-key namespace; the canonical {@code image_summary:&lt;hash&gt;} entries covering the
     * same rows are invalidated through the eviction pipeline.
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
     * Persists an image summary, then publishes cache eviction for it.
     */
    @Transactional
    public ImageSummaryEntity saveImageSummary(ImageSummaryEntity entity) {
        log.info("Persisting image summary record for hash: [{}]", entity.getImageHash());
        ImageSummaryEntity saved = imageSummaryRepository.save(entity);
        publishChange(CacheNames.IMAGE_SUMMARY, CacheNames.IMAGE_SUMMARY_LIST,
                keyOrId(saved.getImageHash(), saved.getId()), saved.getFilePath());
        return saved;
    }

    /**
     * Deletes all image summaries of one session, publishing one eviction per removed row.
     */
    @Transactional
    public void deleteSessionImageSummaries(String uuid) {
        List<ImageSummaryEntity> saved = imageSummaryRepository.findByFilePathContainingUuid(uuid);
        for (ImageSummaryEntity imageSummaryEntity : saved) {
            imageSummaryRepository.deleteById(imageSummaryEntity.getId());
            publishChange(CacheNames.IMAGE_SUMMARY, CacheNames.IMAGE_SUMMARY_LIST,
                    keyOrId(imageSummaryEntity.getImageHash(), imageSummaryEntity.getId()),
                    Optional.ofNullable(uuid));
        }
        log.info("Deleted [{}] image summary record(s) for session [{}]", saved.size(), uuid);
    }

    /**
     * Deletes a single image summary record by ID. No-op when absent.
     */
    @Transactional
    public void deleteImageSummaryById(String id) {
        log.info("Request to delete image summary record for ID: [{}]", id);
        Optional<ImageSummaryEntity> existing = imageSummaryRepository.findById(id);
        if (existing.isPresent()) {
            ImageSummaryEntity entity = existing.get();
            imageSummaryRepository.deleteById(id);
            publishChange(CacheNames.IMAGE_SUMMARY, CacheNames.IMAGE_SUMMARY_LIST,
                    keyOrId(entity.getImageHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully deleted image summary record for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes image summary records by IDs (hashes). Missing IDs are skipped silently.
     */
    @Transactional
    public void deleteImageSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] image summary records.", ids.size());
        for (String id : ids) {
            Optional<ImageSummaryEntity> existing = imageSummaryRepository.findById(id);
            if (existing.isPresent()) {
                ImageSummaryEntity entity = existing.get();
                imageSummaryRepository.deleteById(id);
                publishChange(CacheNames.IMAGE_SUMMARY, CacheNames.IMAGE_SUMMARY_LIST,
                        keyOrId(entity.getImageHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch deletion for provided IDs.");
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
     * Returns the cached emoji summary string, loading from DB on miss.
     */
    public Optional<String> getEmojiSummary(String hash) {
        return readCachedSingle(CacheNames.EMOJI_SUMMARY, hash,
                () -> emojiSummaryRepository.findByEmojiHash(hash)
                        .map(EmojiSummaryEntity::getSummary));
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
     * Persists an emoji summary, then publishes cache eviction for it.
     */
    @Transactional
    public EmojiSummaryEntity saveEmojiSummary(EmojiSummaryEntity entity) {
        log.info("Persisting emoji summary record for hash: [{}]", entity.getEmojiHash());
        EmojiSummaryEntity saved = emojiSummaryRepository.save(entity);
        publishChange(CacheNames.EMOJI_SUMMARY, CacheNames.EMOJI_SUMMARY_LIST,
                keyOrId(saved.getEmojiHash(), saved.getId()), saved.getFilePath());
        return saved;
    }

    /**
     * Deletes all emoji summaries of one session, publishing one eviction per removed row.
     */
    @Transactional
    public void deleteSessionEmojiSummaries(String uuid) {
        List<EmojiSummaryEntity> saved = emojiSummaryRepository.findByFilePathContainingUuid(uuid);
        for (EmojiSummaryEntity emojiSummaryEntity : saved) {
            emojiSummaryRepository.deleteById(emojiSummaryEntity.getId());
            publishChange(CacheNames.EMOJI_SUMMARY, CacheNames.EMOJI_SUMMARY_LIST,
                    keyOrId(emojiSummaryEntity.getEmojiHash(), emojiSummaryEntity.getId()),
                    Optional.ofNullable(uuid));
        }
        log.info("Deleted [{}] emoji summary record(s) for session [{}]", saved.size(), uuid);
    }

    /**
     * Deletes a single emoji summary record by ID (hash). No-op when absent.
     */
    @Transactional
    public void deleteEmojiSummaryById(String id) {
        log.info("Request to delete emoji summary record for ID: [{}]", id);
        Optional<EmojiSummaryEntity> existing = emojiSummaryRepository.findById(id);
        if (existing.isPresent()) {
            EmojiSummaryEntity entity = existing.get();
            emojiSummaryRepository.deleteById(id);
            publishChange(CacheNames.EMOJI_SUMMARY, CacheNames.EMOJI_SUMMARY_LIST,
                    keyOrId(entity.getEmojiHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully deleted emoji summary record for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes emoji summary records by IDs (hashes). Missing IDs are skipped silently.
     */
    @Transactional
    public void deleteEmojiSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] emoji summary records.", ids.size());
        for (String id : ids) {
            Optional<EmojiSummaryEntity> existing = emojiSummaryRepository.findById(id);
            if (existing.isPresent()) {
                EmojiSummaryEntity entity = existing.get();
                emojiSummaryRepository.deleteById(id);
                publishChange(CacheNames.EMOJI_SUMMARY, CacheNames.EMOJI_SUMMARY_LIST,
                        keyOrId(entity.getEmojiHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch deletion for provided IDs.");
    }

    // =========================================================================
    // 2. AUDIO MEDIA SUMMARY CACHE (DB + Cache Abstraction)
    // =========================================================================

    /**
     * Checks if an audio summary record exists by file hash.
     */
    public Optional<AudioSummary> findAudioSummaryByHash(String hash) {
        return audioSummaryRepository.findByFileHash(hash);
    }

    /**
     * Returns the cached audio summary string, loading from DB on miss.
     */
    public Optional<String> getAudioSummary(String hash) {
        return readCachedSingle(CacheNames.AUDIO_SUMMARY, hash,
                () -> audioSummaryRepository.findByFileHash(hash).map(AudioSummary::getSummary));
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
     * Persists an audio summary, then publishes cache eviction for it.
     */
    @Transactional
    public AudioSummary saveAudioSummary(AudioSummary entity) {
        log.info("Persisting audio summary record for hash: [{}]", entity.getFileHash());
        AudioSummary saved = audioSummaryRepository.save(entity);
        publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                keyOrId(saved.getFileHash(), saved.getId()), saved.getFilePath());
        return saved;
    }

    /**
     * Deletes all audio summaries of one session, publishing one eviction per removed row.
     */
    @Transactional
    public void deleteSessionAudioSummaries(String uuid) {
        List<AudioSummary> saved = audioSummaryRepository.findByFilePathContainingUuid(uuid);
        for (AudioSummary audioSummaryEntity : saved) {
            audioSummaryRepository.deleteById(audioSummaryEntity.getId());
            publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                    keyOrId(audioSummaryEntity.getFileHash(), audioSummaryEntity.getId()),
                    Optional.ofNullable(uuid));
        }
        log.info("Deleted [{}] audio summary record(s) for session [{}]", saved.size(), uuid);
    }

    /**
     * Deletes a single audio summary record by ID (hash). No-op when absent.
     */
    @Transactional
    public void deleteAudioSummaryById(String id) {
        log.info("Request to delete audio summary record for ID: [{}]", id);
        Optional<AudioSummary> existing = audioSummaryRepository.findById(id);
        if (existing.isPresent()) {
            AudioSummary entity = existing.get();
            audioSummaryRepository.deleteById(id);
            publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                    keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully deleted audio summary record for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes audio summary records by IDs (hashes). Missing IDs are skipped silently.
     */
    @Transactional
    public void deleteAudioSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] audio summary records.", ids.size());
        for (String id : ids) {
            Optional<AudioSummary> existing = audioSummaryRepository.findById(id);
            if (existing.isPresent()) {
                AudioSummary entity = existing.get();
                audioSummaryRepository.deleteById(id);
                publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                        keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch deletion for provided IDs.");
    }

    /**
     * Clears ONLY the summary text for a single audio record by ID, keeping the
     * transcript intact.
     */
    @Transactional
    public void clearAudioSummaryTextById(String id) {
        log.info("Request to clear audio summary text for ID: [{}]", id);
        Optional<AudioSummary> existing = audioSummaryRepository.findById(id);
        if (existing.isPresent()) {
            AudioSummary entity = existing.get();
            entity.setSummary(null);
            audioSummaryRepository.save(entity);
            publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                    keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully cleared audio summary text for ID: [{}]", id);
        } else {
            log.warn("Summary clearing skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch clears ONLY the summary text for provided audio record IDs.
     */
    @Transactional
    public void clearAudioSummaryTextsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch summary text clearing aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch clear [{}] audio summary texts.", ids.size());
        for (String id : ids) {
            Optional<AudioSummary> existing = audioSummaryRepository.findById(id);
            if (existing.isPresent()) {
                AudioSummary entity = existing.get();
                entity.setSummary(null);
                audioSummaryRepository.save(entity);
                publishChange(CacheNames.AUDIO_SUMMARY, CacheNames.AUDIO_SUMMARY_LIST,
                        keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch clearing of audio summary texts.");
    }

    // =========================================================================
    // 2.5 VIDEO MEDIA SUMMARY CACHE (DB + Cache Abstraction)
    // =========================================================================

    /**
     * Checks if a video summary record exists by file hash.
     */
    public Optional<VideoSummary> findVideoSummaryByHash(String hash) {
        return videoSummaryRepository.findByFileHash(hash);
    }

    /**
     * Returns the cached video summary string, loading from DB on miss.
     */
    public Optional<String> getVideoSummary(String hash) {
        return readCachedSingle(CacheNames.VIDEO_SUMMARY, hash,
                () -> videoSummaryRepository.findByFileHash(hash).map(VideoSummary::getSummary));
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
     * Persists a video summary, then publishes cache eviction for it.
     */
    @Transactional
    public VideoSummary saveVideoSummary(VideoSummary entity) {
        log.info("Persisting video summary record for hash: [{}]", entity.getFileHash());
        VideoSummary saved = videoSummaryRepository.save(entity);
        publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                keyOrId(saved.getFileHash(), saved.getId()), saved.getFilePath());
        return saved;
    }

    /**
     * Deletes all video summaries of one session, publishing one eviction per removed row.
     */
    @Transactional
    public void deleteSessionVideoSummaries(String uuid) {
        List<VideoSummary> saved = videoSummaryRepository.findByFilePathContainingUuid(uuid);
        for (VideoSummary videoSummaryEntity : saved) {
            videoSummaryRepository.deleteById(videoSummaryEntity.getId());
            publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                    keyOrId(videoSummaryEntity.getFileHash(), videoSummaryEntity.getId()),
                    Optional.ofNullable(uuid));
        }
        log.info("Deleted [{}] video summary record(s) for session [{}]", saved.size(), uuid);
    }

    /**
     * Deletes a single video summary record by ID (hash). No-op when absent.
     */
    @Transactional
    public void deleteVideoSummaryById(String id) {
        log.info("Request to delete video summary record for ID: [{}]", id);
        Optional<VideoSummary> existing = videoSummaryRepository.findById(id);
        if (existing.isPresent()) {
            VideoSummary entity = existing.get();
            videoSummaryRepository.deleteById(id);
            publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                    keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully deleted video summary record for ID: [{}]", id);
        } else {
            log.warn("Deletion skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch deletes video summary records by IDs (hashes). Missing IDs are skipped silently.
     */
    @Transactional
    public void deleteVideoSummariesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch deletion aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch delete [{}] video summary records.", ids.size());
        for (String id : ids) {
            Optional<VideoSummary> existing = videoSummaryRepository.findById(id);
            if (existing.isPresent()) {
                VideoSummary entity = existing.get();
                videoSummaryRepository.deleteById(id);
                publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                        keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch deletion for provided IDs.");
    }

    /**
     * Clears ONLY the summary text for a single video record by ID.
     */
    @Transactional
    public void clearVideoSummaryTextById(String id) {
        log.info("Request to clear video summary text for ID: [{}]", id);
        Optional<VideoSummary> existing = videoSummaryRepository.findById(id);
        if (existing.isPresent()) {
            VideoSummary entity = existing.get();
            entity.setSummary(null);
            entity.setTranscript(null);
            videoSummaryRepository.save(entity);
            publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                    keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            log.info("Successfully cleared video summary text for ID: [{}]", id);
        } else {
            log.warn("Summary clearing skipped. No record found for ID: [{}]", id);
        }
    }

    /**
     * Batch clears ONLY the summary text for provided video record IDs.
     */
    @Transactional
    public void clearVideoSummaryTextsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Batch summary text clearing aborted. Provided ID list is empty or null.");
            return;
        }

        log.info("Request to batch clear [{}] video summary texts.", ids.size());
        for (String id : ids) {
            Optional<VideoSummary> existing = videoSummaryRepository.findById(id);
            if (existing.isPresent()) {
                VideoSummary entity = existing.get();
                entity.setSummary(null);
                entity.setTranscript(null);
                videoSummaryRepository.save(entity);
                publishChange(CacheNames.VIDEO_SUMMARY, CacheNames.VIDEO_SUMMARY_LIST,
                        keyOrId(entity.getFileHash(), entity.getId()), entity.getFilePath());
            }
        }
        log.info("Completed batch clearing of video summary texts.");
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
