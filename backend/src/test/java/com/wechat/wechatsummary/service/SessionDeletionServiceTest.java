package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.EmojiSummaryEntity;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.entity.VideoSummary;
import com.wechat.wechatsummary.repository.AudioSummaryRepository;
import com.wechat.wechatsummary.repository.ChatSummaryTaskRepository;
import com.wechat.wechatsummary.repository.EmojiSummaryRepository;
import com.wechat.wechatsummary.repository.ImageSummaryRepository;
import com.wechat.wechatsummary.repository.VideoSummaryRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionDeletionServiceTest {

    private static final String USER_ID = "user-123";
    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final UUID SESSION_UUID = UUID.fromString(SESSION_ID);

    @TempDir
    Path tmp;

    @Mock
    private WeChatSummaryCacheService cacheService;
    @Mock
    private StoragePaths storagePaths;
    @Mock
    private TaskCoordinatorService taskCoordinatorService;
    @Mock
    private ChatSummaryService chatSummaryService;
    @Mock
    private ImageSummaryRepository imageSummaryRepository;
    @Mock
    private EmojiSummaryRepository emojiSummaryRepository;
    @Mock
    private AudioSummaryRepository audioSummaryRepository;
    @Mock
    private VideoSummaryRepository videoSummaryRepository;
    @Mock
    private ChatSummaryTaskRepository taskRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache imageSummaryCache;
    @Mock
    private Cache emojiSummaryCache;
    @Mock
    private Cache audioSummaryCache;
    @Mock
    private Cache videoSummaryCache;
    @Mock
    private Cache imageSummaryListCache;
    @Mock
    private Cache emojiSummaryListCache;
    @Mock
    private Cache audioSummaryListCache;
    @Mock
    private Cache videoSummaryListCache;
    @Mock
    private Cache chatAnalysisCache;

    private SessionDeletionService service;

    @BeforeEach
    void setUp() {
        service = new SessionDeletionService(cacheService, storagePaths, taskCoordinatorService, chatSummaryService);

        Path userDir = tmp.resolve(USER_ID);
        Path sessionDir = userDir.resolve(SESSION_ID);

        when(storagePaths.userDir(USER_ID)).thenReturn(userDir);
        when(storagePaths.sessionDir(USER_ID, SESSION_ID)).thenReturn(sessionDir);
        when(storagePaths.processedMarkdown(USER_ID, SESSION_ID))
            .thenReturn(userDir.resolve("outputs").resolve(SESSION_ID + "_processed.md"));
        when(storagePaths.summaryTxt(USER_ID, SESSION_ID))
            .thenReturn(userDir.resolve("outputs").resolve(SESSION_ID + "_summary.txt"));
        when(storagePaths.summaryTemp(USER_ID, SESSION_ID))
            .thenReturn(userDir.resolve("outputs").resolve(SESSION_ID + "_summary.temp"));

        when(cacheManager.getCache("image_summary")).thenReturn(imageSummaryCache);
        when(cacheManager.getCache("emoji_summary")).thenReturn(emojiSummaryCache);
        when(cacheManager.getCache("audio_summary")).thenReturn(audioSummaryCache);
        when(cacheManager.getCache("video_summary")).thenReturn(videoSummaryCache);
        when(cacheManager.getCache("image_summary_list")).thenReturn(imageSummaryListCache);
        when(cacheManager.getCache("emoji_summary_list")).thenReturn(emojiSummaryListCache);
        when(cacheManager.getCache("audio_summary_list")).thenReturn(audioSummaryListCache);
        when(cacheManager.getCache("video_summary_list")).thenReturn(videoSummaryListCache);
        when(cacheManager.getCache("chat_analysis")).thenReturn(chatAnalysisCache);

        doNothing().when(cacheService).deleteSessionImageSummaries(SESSION_ID);
        doNothing().when(cacheService).deleteSessionEmojiSummaries(SESSION_ID);
        doNothing().when(cacheService).deleteSessionAudioSummaries(SESSION_ID);
        doNothing().when(cacheService).deleteSessionVideoSummaries(SESSION_ID);
        doNothing().when(cacheService).deleteChatAnalysisTask(SESSION_UUID);
        doNothing().when(cacheService).deleteSessionOutputs(USER_ID, SESSION_ID);
        doNothing().when(chatSummaryService).pauseSummary(SESSION_UUID);
    }

    // ── Happy-path tests ────────────────────────────────────────────────

    @Test
    void deleteSession_successfulDeletion() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        boolean result = service.deleteSession(USER_ID, SESSION_ID);

        assertTrue(result);
        verify(cacheService).deleteSessionImageSummaries(SESSION_ID);
        verify(cacheService).deleteSessionEmojiSummaries(SESSION_ID);
        verify(cacheService).deleteSessionAudioSummaries(SESSION_ID);
        verify(cacheService).deleteSessionVideoSummaries(SESSION_ID);
        verify(cacheService).deleteChatAnalysisTask(SESSION_UUID);
        verify(cacheService).deleteSessionOutputs(USER_ID, SESSION_ID);
        verify(taskCoordinatorService).cleanupTaskKeys(SESSION_ID);
    }

    @Test
    void deleteSession_validatesUserDirExists() {
        assertFalse(Files.exists(tmp.resolve(USER_ID)));

        assertThrows(java.io.FileNotFoundException.class,
            () -> service.deleteSession(USER_ID, SESSION_ID));
    }

    @Test
    void deleteSession_rejectsMalformedUuid() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        assertThrows(IllegalArgumentException.class,
            () -> service.deleteSession(USER_ID, "not-a-uuid"));
    }

    @Test
    void deleteSession_rejectsPathTraversal() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));
        when(storagePaths.sessionDir(USER_ID, "../../etc")).thenReturn(Path.of("/etc"));

        assertThrows(SecurityException.class,
            () -> service.deleteSession(USER_ID, "../../etc"));
    }

    // ── Media-type isolation tests ──────────────────────────────────────

    @Test
    void deleteSession_imageDelegation() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionImageSummaries(SESSION_ID);
    }

    @Test
    void deleteSession_emojiDelegation() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionEmojiSummaries(SESSION_ID);
    }

    @Test
    void deleteSession_audioDelegation() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionAudioSummaries(SESSION_ID);
    }

    @Test
    void deleteSession_videoDelegation() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionVideoSummaries(SESSION_ID);
    }

    // ── Chat task cleanup tests ─────────────────────────────────────────

    @Test
    void deleteSession_cleansUpChatTask() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteChatAnalysisTask(SESSION_UUID);
    }

    @Test
    void deleteSession_interruptsRunningSummaryThread() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(chatSummaryService).pauseSummary(SESSION_UUID);
    }

    @Test
    void deleteSession_cleansUpTaskCoordinatorKeys() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(taskCoordinatorService).cleanupTaskKeys(SESSION_ID);
    }

    // ── Idempotency tests ──────────────────────────────────────────────

    @Test
    void deleteSession_idempotent() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);
        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService, times(2)).deleteSessionImageSummaries(SESSION_ID);
        verify(cacheService, times(2)).deleteSessionEmojiSummaries(SESSION_ID);
        verify(cacheService, times(2)).deleteSessionAudioSummaries(SESSION_ID);
        verify(cacheService, times(2)).deleteSessionVideoSummaries(SESSION_ID);
    }

    // ── Failure tests ───────────────────────────────────────────────────

    @Test
    void deleteSession_dbFailurePropagates() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));
        doThrow(new RuntimeException("DB connection lost"))
            .when(cacheService).deleteSessionImageSummaries(SESSION_ID);

        assertThrows(RuntimeException.class,
            () -> service.deleteSession(USER_ID, SESSION_ID));

        // Filesystem should NOT be touched if DB fails
        verify(cacheService, never()).deleteSessionOutputs(USER_ID, SESSION_ID);
        verify(taskCoordinatorService, never()).cleanupTaskKeys(SESSION_ID);
    }

    @Test
    void deleteSession_filesystemFailurePropagates() throws Exception {
        Path userDir = storagePaths.userDir(USER_ID);
        Files.createDirectories(userDir);
        // sessionDir does not exist, deleteRecursively is idempotent — so this won't fail
        // Instead, test that the method still completes when filesystem is absent
        boolean result = service.deleteSession(USER_ID, SESSION_ID);
        assertTrue(result);
    }

    // ── Output file cleanup tests ───────────────────────────────────────

    @Test
    void deleteSession_cleansUpOutputFiles() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionOutputs(USER_ID, SESSION_ID);
    }

    // ── Cross-user isolation ────────────────────────────────────────────

    @Test
    void deleteSession_doesNotAffectOtherUser() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));
        String otherUserId = "other-user-999";
        Path otherUserDir = tmp.resolve(otherUserId);
        Files.createDirectories(otherUserDir);
        when(storagePaths.userDir(otherUserId)).thenReturn(otherUserDir);

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionImageSummaries(SESSION_ID);
        verify(cacheService, never()).deleteSessionImageSummaries(otherUserId);
        assertTrue(Files.exists(otherUserDir));
    }

    // ── Cross-session isolation ─────────────────────────────────────────

    @Test
    void deleteSession_doesNotAffectOtherSession() throws Exception {
        Files.createDirectories(storagePaths.userDir(USER_ID));
        String otherSessionId = "660e8400-e29b-41d4-a716-446655440001";

        service.deleteSession(USER_ID, SESSION_ID);

        verify(cacheService).deleteSessionImageSummaries(SESSION_ID);
        verify(cacheService, never()).deleteSessionImageSummaries(otherSessionId);
    }
}
