package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wechat.wechatsummary.cache.CacheEvictionPublisher;
import com.wechat.wechatsummary.cache.CacheNames;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.EmojiSummaryEntity;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.entity.VideoSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ChatSummaryTaskRepository;
import com.wechat.wechatsummary.repository.EmojiSummaryRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import com.wechat.wechatsummary.repository.VideoSummaryRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The facade contract: DB writes publish eviction for the changed entry plus the session list,
 * no-ops publish nothing, reads are cache-aside, and no infrastructure concept leaks through
 * the public API.
 */
@ExtendWith(MockitoExtension.class)
class WeChatSummaryCacheServiceTest {

    private static final String SESSION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String FILE = "/app/uploads/user-1/" + SESSION + "/images/1.jpg";

    @Mock
    private ImageSummaryRepository imageRepo;
    @Mock
    private AudioSummaryRepository audioRepo;
    @Mock
    private VideoSummaryRepository videoRepo;
    @Mock
    private EmojiSummaryRepository emojiRepo;
    @Mock
    private ChatSummaryTaskRepository taskRepo;
    @Mock
    private CacheEvictionPublisher publisher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private StoragePaths storagePaths;
    @Mock
    private Cache singleCache;

    private WeChatSummaryCacheService service;

    @BeforeEach
    void setUp() {
        service = new WeChatSummaryCacheService(
                imageRepo, audioRepo, videoRepo, emojiRepo, taskRepo,
                publisher, redisTemplate, cacheManager, storagePaths);
        Mockito.lenient().when(cacheManager.getCache(anyString())).thenReturn(singleCache);
    }

    private ImageSummaryEntity imageEntity() {
        ImageSummaryEntity e = new ImageSummaryEntity();
        e.setId("img-hash");
        e.setImageHash("img-hash");
        e.setFilePath(FILE);
        e.setSummary("img summary");
        return e;
    }

    // --- Saves publish single + session list eviction ---

    @Test
    void saveImageSummary_publishesEviction() {
        org.mockito.Mockito.when(imageRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveImageSummary(imageEntity());

        verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
        verify(publisher).evict(CacheNames.IMAGE_SUMMARY_LIST, SESSION);
        verify(publisher, never()).clear(anyString());
    }

    @Test
    void saveImageSummary_unknownSession_clearsList() {
        ImageSummaryEntity e = imageEntity();
        e.setFilePath("/somewhere/else/1.jpg");
        org.mockito.Mockito.when(imageRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveImageSummary(e);

        verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
        verify(publisher, never()).evict(anyString(), org.mockito.ArgumentMatchers.eq(SESSION));
        verify(publisher).clear(CacheNames.IMAGE_SUMMARY_LIST);
    }

    @Test
    void saveAudioVideoEmojiSummaries_publishTheirCaches() {
        AudioSummary a = new AudioSummary();
        a.setId("a-1");
        a.setFileHash("a-1");
        a.setFilePath("/app/uploads/u/" + SESSION + "/voices/1.amr");
        a.setSummary("s");
        a.setCreatedAt(LocalDateTime.now());
        VideoSummary v = new VideoSummary();
        v.setId("v-1");
        v.setFileHash("v-1");
        v.setFilePath("/app/uploads/u/" + SESSION + "/videos/1.mp4");
        v.setSummary("s");
        v.setCreatedAt(LocalDateTime.now());
        EmojiSummaryEntity e = new EmojiSummaryEntity();
        e.setId("e-1");
        e.setEmojiHash("e-1");
        e.setFilePath("/app/uploads/u/" + SESSION + "/emojis/1.gif");
        e.setSummary("s");
        org.mockito.Mockito.when(audioRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.when(videoRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.when(emojiRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveAudioSummary(a);
        service.saveVideoSummary(v);
        service.saveEmojiSummary(e);

        verify(publisher).evict(CacheNames.AUDIO_SUMMARY, "a-1");
        verify(publisher).evict(CacheNames.AUDIO_SUMMARY_LIST, SESSION);
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY, "v-1");
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY_LIST, SESSION);
        verify(publisher).evict(CacheNames.EMOJI_SUMMARY, "e-1");
        verify(publisher).evict(CacheNames.EMOJI_SUMMARY_LIST, SESSION);
    }

    // --- Deletes ---

    @Test
    void deleteImageSummaryById_existing_publishesEviction() {
        org.mockito.Mockito.when(imageRepo.findById("img-hash"))
                .thenReturn(Optional.of(imageEntity()));

        service.deleteImageSummaryById("img-hash");

        verify(imageRepo).deleteById("img-hash");
        verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
        verify(publisher).evict(CacheNames.IMAGE_SUMMARY_LIST, SESSION);
    }

    @Test
    void deleteImageSummaryById_missing_publishesNothing() {
        org.mockito.Mockito.when(imageRepo.findById("nope")).thenReturn(Optional.empty());

        service.deleteImageSummaryById("nope");

        verify(imageRepo, never()).deleteById(anyString());
        verify(publisher, never()).evict(anyString(), anyString());
        verify(publisher, never()).clear(anyString());
    }

    @Test
    void deleteImageSummariesByIds_onlyExistingPublish() {
        org.mockito.Mockito.when(imageRepo.findById("img-hash"))
                .thenReturn(Optional.of(imageEntity()));
        org.mockito.Mockito.when(imageRepo.findById("ghost")).thenReturn(Optional.empty());

        service.deleteImageSummariesByIds(List.of("img-hash", "ghost"));

        verify(imageRepo).deleteById("img-hash");
        verify(imageRepo, never()).deleteById("ghost");
        verify(publisher, times(1)).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
        verify(publisher, times(1)).evict(CacheNames.IMAGE_SUMMARY_LIST, SESSION);
    }

    @Test
    void deleteSessionImageSummaries_publishesOneEvictionPerRow() {
        ImageSummaryEntity second = imageEntity();
        second.setId("img-hash-2");
        second.setImageHash("img-hash-2");
        org.mockito.Mockito.when(imageRepo.findByFilePathContainingUuid(SESSION))
                .thenReturn(List.of(imageEntity(), second));

        service.deleteSessionImageSummaries(SESSION);

        verify(imageRepo).deleteById("img-hash");
        verify(imageRepo).deleteById("img-hash-2");
        verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
        verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash-2");
        verify(publisher, times(2)).evict(CacheNames.IMAGE_SUMMARY_LIST, SESSION);
    }

    @Test
    void deleteSessionAudioVideoEmojiSummaries_publishSessionScopedEviction() {
        AudioSummary a = new AudioSummary();
        a.setId("a-1");
        a.setFileHash("a-1");
        a.setFilePath("/x/" + SESSION + "/voices/1.amr");
        VideoSummary v = new VideoSummary();
        v.setId("v-1");
        v.setFileHash("v-1");
        v.setFilePath("/x/" + SESSION + "/videos/1.mp4");
        EmojiSummaryEntity e = new EmojiSummaryEntity();
        e.setId("e-1");
        e.setEmojiHash("e-1");
        e.setFilePath("/x/" + SESSION + "/emojis/1.gif");
        org.mockito.Mockito.when(audioRepo.findByFilePathContainingUuid(SESSION))
                .thenReturn(List.of(a));
        org.mockito.Mockito.when(videoRepo.findByFilePathContainingUuid(SESSION))
                .thenReturn(List.of(v));
        org.mockito.Mockito.when(emojiRepo.findByFilePathContainingUuid(SESSION))
                .thenReturn(List.of(e));

        service.deleteSessionAudioSummaries(SESSION);
        service.deleteSessionVideoSummaries(SESSION);
        service.deleteSessionEmojiSummaries(SESSION);

        verify(publisher).evict(CacheNames.AUDIO_SUMMARY, "a-1");
        verify(publisher).evict(CacheNames.AUDIO_SUMMARY_LIST, SESSION);
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY, "v-1");
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY_LIST, SESSION);
        verify(publisher).evict(CacheNames.EMOJI_SUMMARY, "e-1");
        verify(publisher).evict(CacheNames.EMOJI_SUMMARY_LIST, SESSION);
    }

    @Test
    void clearAudioAndVideoSummaryText_publishEviction() {
        AudioSummary a = new AudioSummary();
        a.setId("a-1");
        a.setFileHash("a-1");
        a.setFilePath("/x/" + SESSION + "/voices/1.amr");
        a.setSummary("old");
        VideoSummary v = new VideoSummary();
        v.setId("v-1");
        v.setFileHash("v-1");
        v.setFilePath("/x/" + SESSION + "/videos/1.mp4");
        v.setSummary("old");
        org.mockito.Mockito.when(audioRepo.findById("a-1")).thenReturn(Optional.of(a));
        org.mockito.Mockito.when(audioRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.when(videoRepo.findById("v-1")).thenReturn(Optional.of(v));
        org.mockito.Mockito.when(videoRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.clearAudioSummaryTextById("a-1");
        service.clearVideoSummaryTextById("v-1");

        verify(publisher).evict(CacheNames.AUDIO_SUMMARY, "a-1");
        verify(publisher).evict(CacheNames.AUDIO_SUMMARY_LIST, SESSION);
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY, "v-1");
        verify(publisher).evict(CacheNames.VIDEO_SUMMARY_LIST, SESSION);
    }

    // --- Reads are cache-aside ---

    @Test
    void getImageSummary_missLoadsDbAndPopulatesCache_hitAvoidsDb() {
        org.mockito.Mockito.when(singleCache.get("img-hash", String.class))
                .thenReturn(null, "img summary");
        org.mockito.Mockito.when(imageRepo.findByImageHash("img-hash"))
                .thenReturn(Optional.of(imageEntity()));

        assertEquals(Optional.of("img summary"), service.getImageSummary("img-hash"));
        // Second call served from cache: no second DB hit, and the value was stored.
        assertEquals(Optional.of("img summary"), service.getImageSummary("img-hash"));

        verify(imageRepo, times(1)).findByImageHash("img-hash");
        verify(singleCache).put("img-hash", "img summary");
    }

    @Test
    void getAudioSummary_nullSummaryIsNotCached() {
        AudioSummary transcriptOnly = new AudioSummary();
        transcriptOnly.setTranscript("t");
        org.mockito.Mockito.when(singleCache.get("a-1", String.class)).thenReturn(null);
        org.mockito.Mockito.when(audioRepo.findByFileHash("a-1"))
                .thenReturn(Optional.of(transcriptOnly));

        assertEquals(Optional.empty(), service.getAudioSummary("a-1"));
        assertEquals(Optional.empty(), service.getAudioSummary("a-1"));

        // Null summaries must keep hitting the DB (no ambiguous null-in-cache).
        verify(audioRepo, times(2)).findByFileHash("a-1");
        verify(singleCache, never()).put(anyString(), any());
    }

    @Test
    void getImageSummaryByMd5_isPureDbLookup() {
        org.mockito.Mockito.when(imageRepo.findByFilePathContaining("md5frag"))
                .thenReturn(List.of(imageEntity()));

        assertEquals(Optional.of("img summary"), service.getImageSummaryByMd5("md5frag"));

        verify(cacheManager, never()).getCache(anyString());
        verify(imageRepo, never()).findByImageHash(anyString());
    }

    // --- Transactional coupling: publish only on commit ---

    @Test
    void saveImageSummary_insideTransaction_publishesOnlyAfterCommit() {
        org.mockito.Mockito.when(imageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        org.springframework.transaction.support.TransactionSynchronizationManager
                .initSynchronization();
        try {
            service.saveImageSummary(imageEntity());

            // DB write happened, but nothing published yet: still inside the transaction.
            verify(imageRepo).save(any());
            verify(publisher, never()).evict(anyString(), anyString());

            // Commit fires afterCommit: now the evictions go out.
            for (var sync : org.springframework.transaction.support
                    .TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(publisher).evict(CacheNames.IMAGE_SUMMARY, "img-hash");
            verify(publisher).evict(CacheNames.IMAGE_SUMMARY_LIST, SESSION);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    @Test
    void saveImageSummary_rollback_producesNoMessage() {
        org.mockito.Mockito.when(imageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        org.springframework.transaction.support.TransactionSynchronizationManager
                .initSynchronization();
        try {
            service.saveImageSummary(imageEntity());

            // Rollback: the container fires afterCompletion(ROLLED_BACK), never afterCommit.
            for (var sync : org.springframework.transaction.support
                    .TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(org.springframework.transaction.support
                        .TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(publisher, never()).evict(anyString(), anyString());
            verify(publisher, never()).clear(anyString());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    // --- Black-box API surface ---

    @Test
    void publicApi_exposesNoInfrastructureConcepts() {
        for (Method m : WeChatSummaryCacheService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            String name = m.getName().toLowerCase();
            assertTrue(
                    !name.contains("publish")
                            && !name.contains("evict")
                            && !name.contains("schedule")
                            && !name.contains("outbox")
                            && !(name.startsWith("put") && name.contains("summary")),
                    "public method leaks infrastructure: " + m.getName());
            for (Class<?> param : m.getParameterTypes()) {
                assertTrue(
                        !param.getSimpleName().contains("Rabbit")
                                && !param.getSimpleName().contains("Outbox")
                                && !param.getSimpleName().contains("Eviction")
                                && !param.getSimpleName().contains("Channel"),
                        "public method exposes infrastructure type: " + m.getName());
            }
        }
        for (var field : WeChatSummaryCacheService.class.getDeclaredFields()) {
            assertTrue(
                    !field.getType().getSimpleName().contains("RabbitTemplate")
                            && !field.getType().getSimpleName().contains("Outbox"),
                    "service must not depend on broker/outbox types, found: "
                            + field.getType().getSimpleName());
        }
    }
}
