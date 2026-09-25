package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.dto.AiSettingsUpdateRequest;
import com.wechat.wechatsummary.dto.AiSettingsView;
import com.wechat.wechatsummary.entity.AppSetting;
import com.wechat.wechatsummary.exception.BadRequestException;
import com.wechat.wechatsummary.repository.AppSettingRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiSettingsServiceTest {

    @Mock
    private AppSettingRepository repository;

    private AiSettingsService service;

    /** In-memory fake backing the repository mock so views reflect saves. */
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        service = new AiSettingsService(
            repository,
            "env-gemini-key", "https://default-chat/v1", "default-chat-model",
            Duration.ofSeconds(600), 0.1,
            "env-image-key", "https://default-image/v1", "default-image-model",
            Duration.ofSeconds(120),
            "env-video-key", "https://default-video/v1", "default-video-model",
            Duration.ofSeconds(120),
            "", "http://default-whisper/v1", "default-whisper-model",
            Duration.ofSeconds(300),
            3, 10, 5, 10, 10);

        when(repository.findAll()).thenAnswer(inv -> store.entrySet().stream()
            .map(e -> {
                AppSetting s = new AppSetting();
                s.setKey(e.getKey());
                s.setValue(e.getValue());
                return s;
            })
            .toList());
        when(repository.findById(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(store.get(key)).map(v -> {
                AppSetting s = new AppSetting();
                s.setKey(key);
                s.setValue(v);
                return s;
            });
        });
        when(repository.save(any())).thenAnswer(inv -> {
            AppSetting s = inv.getArgument(0);
            store.put(s.getKey(), s.getValue());
            return s;
        });
        doAnswer(inv -> {
            store.clear();
            return null;
        }).when(repository).deleteAll();
    }

    private static AiSettingsUpdateRequest update(
        String chatApiKey, String chatBaseUrl, String chatModel,
        Integer workers, Integer maxWorkers, Integer prefetch,
        Integer aiMaxParallel, Integer aiThrottlePercent) {
        return new AiSettingsUpdateRequest(
            chatApiKey, chatBaseUrl, chatModel,
            null, null, null,
            null, null, null,
            null, null, null,
            workers, maxWorkers, prefetch, aiMaxParallel, aiThrottlePercent);
    }

    @Test
    void viewMasksSecretsAndFallsBackToDefaults() {
        store.put(AiSettingsService.CHAT_API_KEY, "sk-live-1234567890");

        AiSettingsView view = service.view();

        assertEquals("••••7890", view.chatApiKey());
        assertEquals("https://default-chat/v1", view.chatBaseUrl());
        assertEquals("••••-key", view.imageApiKey(), "non-blank default key is masked, not hidden");
        assertNull(view.transcriptionApiKey(), "blank default stays null (no key configured)");
        assertEquals(3, view.workers());
        assertEquals(10, view.maxWorkers());
        assertEquals(5, view.prefetch());
    }

    @Test
    void updateStoresNonBlankAndIgnoresBlank() {
        service.update(update("   ", null, "gpt-4o", null, null, null, null, null));

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(repository).save(captor.capture());
        assertEquals(AiSettingsService.CHAT_MODEL, captor.getValue().getKey());
        assertEquals("gpt-4o", captor.getValue().getValue());
    }

    @Test
    void updateStoresConcurrencyNumbersAndViewReflectsThem() {
        AiSettingsView view = service.update(update(null, null, null, 6, 12, 7, 8, 50));

        assertEquals("6", store.get(AiSettingsService.PREPROCESS_WORKERS));
        assertEquals("50", store.get(AiSettingsService.PREPROCESS_AI_THROTTLE_PERCENT));
        assertEquals(6, view.workers());
        assertEquals(12, view.maxWorkers());
        assertEquals(7, view.prefetch());
    }

    @Test
    void updateRejectsOutOfRangeNumbers() {
        assertThrows(BadRequestException.class,
            () -> service.update(update(null, null, null, 99, null, null, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsMaxWorkersBelowWorkers() {
        assertThrows(BadRequestException.class,
            () -> service.update(update(null, null, null, 8, 2, null, null, null)));
    }

    @Test
    void resetDeletesEverythingAndRestoresDefaults() {
        store.put(AiSettingsService.CHAT_MODEL, "gpt-4o");
        store.put(AiSettingsService.PREPROCESS_WORKERS, "9");

        AiSettingsView view = service.reset();

        verify(repository).deleteAll();
        assertEquals("default-chat-model", view.chatModel());
        assertEquals(3, view.workers());
    }

    @Test
    void aiPermitsUsesDefaults() {
        // total=max(3,10)=10, pct=round(10*10/100)=1, min(1,10)=1
        assertEquals(1, service.aiPermits());
    }

    @Test
    void aiPermitsFollowsOverrides() {
        store.put(AiSettingsService.PREPROCESS_MAX_WORKERS, "20");
        store.put(AiSettingsService.PREPROCESS_AI_THROTTLE_PERCENT, "50");
        store.put(AiSettingsService.PREPROCESS_AI_MAX_PARALLEL, "10");
        // total=20, pct=round(20*50/100)=10, min(10,10)=10
        assertEquals(10, service.aiPermits());
    }

    @Test
    void aiPermitsNeverDropsBelowOne() {
        store.put(AiSettingsService.PREPROCESS_AI_MAX_PARALLEL, "1");
        store.put(AiSettingsService.PREPROCESS_AI_THROTTLE_PERCENT, "1");
        assertEquals(1, service.aiPermits());
    }
}
