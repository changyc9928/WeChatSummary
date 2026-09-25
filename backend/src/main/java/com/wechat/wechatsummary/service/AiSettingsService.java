package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.dto.AiSettingsUpdateRequest;
import com.wechat.wechatsummary.dto.AiSettingsView;
import com.wechat.wechatsummary.entity.AppSetting;
import com.wechat.wechatsummary.exception.BadRequestException;
import com.wechat.wechatsummary.repository.AppSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-wide AI provider settings backing the UI settings sidebar.
 *
 * <p>Each slot is named by its <em>function</em> (chat, image, video, transcription) and accepts
 * any OpenAI-compatible provider: base URL + API key + model. An empty API key enables no-auth
 * mode (no {@code Authorization} header is sent), which suits local servers such as Ollama,
 * LM&nbsp;Studio or self-hosted Whisper.
 *
 * <p>A stored row overrides the corresponding environment default; when no row exists the
 * {@code application.yaml} / environment value applies (shipped defaults: a Gemini-compatible
 * chat endpoint, NVIDIA vision endpoints, local Whisper). Overrides take effect on the next AI
 * call ({@link AiModelFactory} rebuilds its clients when the effective values change), so no
 * restart is needed.
 */
@Service
@Slf4j
public class AiSettingsService {

    public static final String CHAT_API_KEY = "chat.api-key";
    public static final String CHAT_BASE_URL = "chat.base-url";
    public static final String CHAT_MODEL = "chat.model";

    public static final String IMAGE_API_KEY = "image.api-key";
    public static final String IMAGE_BASE_URL = "image.base-url";
    public static final String IMAGE_MODEL = "image.model";

    public static final String VIDEO_API_KEY = "video.api-key";
    public static final String VIDEO_BASE_URL = "video.base-url";
    public static final String VIDEO_MODEL = "video.model";

    public static final String TRANSCRIPTION_API_KEY = "transcription.api-key";
    public static final String TRANSCRIPTION_BASE_URL = "transcription.base-url";
    public static final String TRANSCRIPTION_MODEL = "transcription.model";

    public static final String PREPROCESS_WORKERS = "preprocess.workers";
    public static final String PREPROCESS_MAX_WORKERS = "preprocess.max-workers";
    public static final String PREPROCESS_PREFETCH = "preprocess.prefetch";
    public static final String PREPROCESS_AI_MAX_PARALLEL = "preprocess.ai-max-parallel";
    public static final String PREPROCESS_AI_THROTTLE_PERCENT = "preprocess.ai-throttle-percent";

    /** Effective settings snapshot; never contains nulls (blank means no-auth). */
    public record EffectiveAiSettings(
        String chatApiKey,
        String chatBaseUrl,
        String chatModel,
        String imageApiKey,
        String imageBaseUrl,
        String imageModel,
        String videoApiKey,
        String videoBaseUrl,
        String videoModel,
        String transcriptionApiKey,
        String transcriptionBaseUrl,
        String transcriptionModel) {
    }

    /** Effective preprocessing concurrency snapshot; never contains nulls. */
    public record EffectivePreprocess(
        int workers,
        int maxWorkers,
        int prefetch,
        int aiMaxParallel,
        int aiThrottlePercent) {
    }

    private final AppSettingRepository repository;

    private final String defaultChatApiKey;
    private final String defaultChatBaseUrl;
    private final String defaultChatModel;
    private final Duration chatTimeout;
    private final Double chatTemperature;

    private final String defaultImageApiKey;
    private final String defaultImageBaseUrl;
    private final String defaultImageModel;
    private final Duration imageTimeout;

    private final String defaultVideoApiKey;
    private final String defaultVideoBaseUrl;
    private final String defaultVideoModel;
    private final Duration videoTimeout;

    private final String defaultTranscriptionApiKey;
    private final String defaultTranscriptionBaseUrl;
    private final String defaultTranscriptionModel;
    private final Duration transcriptionTimeout;

    private final int defaultWorkers;
    private final int defaultMaxWorkers;
    private final int defaultPrefetch;
    private final int defaultAiMaxParallel;
    private final int defaultAiThrottlePercent;

    public AiSettingsService(
        AppSettingRepository repository,
        @Value("${spring.ai.openai.api-key:}") String chatApiKey,
        @Value("${spring.ai.openai.base-url:https://generativelanguage.googleapis.com/v1beta/openai/}") String chatBaseUrl,
        @Value("${spring.ai.openai.chat.options.model:gemini-3.5-flash-lite}") String chatModel,
        @Value("${spring.ai.openai.chat.timeout:600s}") Duration chatTimeout,
        @Value("${spring.ai.openai.chat.temperature:0.1}") Double chatTemperature,
        @Value("${custom-ai.multimodal.api-key:}") String imageApiKey,
        @Value("${custom-ai.multimodal.base-url:https://integrate.api.nvidia.com/v1}") String imageBaseUrl,
        @Value("${custom-ai.multimodal.model:meta/llama-3.2-11b-vision-instruct}") String imageModel,
        @Value("${custom-ai.multimodal.timeout:120s}") Duration imageTimeout,
        @Value("${custom-ai.video.api-key:}") String videoApiKey,
        @Value("${custom-ai.video.base-url:https://integrate.api.nvidia.com/v1}") String videoBaseUrl,
        @Value("${custom-ai.video.model:meta/llama-3.2-11b-vision-instruct}") String videoModel,
        @Value("${custom-ai.video.timeout:120s}") Duration videoTimeout,
        @Value("${transcription.api-key:}") String transcriptionApiKey,
        @Value("${spring.ai.openai.audio.transcription.base-url:http://localhost:48000/v1/}") String transcriptionBaseUrl,
        @Value("${spring.ai.openai.audio.transcription.options.model:large-v3}") String transcriptionModel,
        @Value("${spring.ai.openai.audio.transcription.timeout:300s}") Duration transcriptionTimeout,
        @Value("${rabbit.concurrent-consumers:3}") int workers,
        @Value("${rabbit.max-concurrent-consumers:10}") int maxWorkers,
        @Value("${rabbit.prefetch-count:5}") int prefetch,
        @Value("${custom-ai.multimodal.max-concurrent-requests:10}") int aiMaxParallel,
        @Value("${custom-ai.multimodal.max-concurrent-percentage:10}") int aiThrottlePercent) {
        this.repository = repository;
        this.defaultChatApiKey = chatApiKey;
        this.defaultChatBaseUrl = chatBaseUrl;
        this.defaultChatModel = chatModel;
        this.chatTimeout = chatTimeout;
        this.chatTemperature = chatTemperature;
        this.defaultImageApiKey = imageApiKey;
        this.defaultImageBaseUrl = imageBaseUrl;
        this.defaultImageModel = imageModel;
        this.imageTimeout = imageTimeout;
        this.defaultVideoApiKey = videoApiKey;
        this.defaultVideoBaseUrl = videoBaseUrl;
        this.defaultVideoModel = videoModel;
        this.videoTimeout = videoTimeout;
        this.defaultTranscriptionApiKey = transcriptionApiKey;
        this.defaultTranscriptionBaseUrl = transcriptionBaseUrl;
        this.defaultTranscriptionModel = transcriptionModel;
        this.transcriptionTimeout = transcriptionTimeout;
        this.defaultWorkers = workers;
        this.defaultMaxWorkers = maxWorkers;
        this.defaultPrefetch = prefetch;
        this.defaultAiMaxParallel = aiMaxParallel;
        this.defaultAiThrottlePercent = aiThrottlePercent;
    }

    /** Resolves the currently effective value for every known key. */
    @Transactional(readOnly = true)
    public EffectiveAiSettings effective() {
        Map<String, String> stored = loadAll();
        return new EffectiveAiSettings(
            orDefault(stored, CHAT_API_KEY, defaultChatApiKey),
            orDefault(stored, CHAT_BASE_URL, defaultChatBaseUrl),
            orDefault(stored, CHAT_MODEL, defaultChatModel),
            orDefault(stored, IMAGE_API_KEY, defaultImageApiKey),
            orDefault(stored, IMAGE_BASE_URL, defaultImageBaseUrl),
            orDefault(stored, IMAGE_MODEL, defaultImageModel),
            orDefault(stored, VIDEO_API_KEY, defaultVideoApiKey),
            orDefault(stored, VIDEO_BASE_URL, defaultVideoBaseUrl),
            orDefault(stored, VIDEO_MODEL, defaultVideoModel),
            orDefault(stored, TRANSCRIPTION_API_KEY, defaultTranscriptionApiKey),
            orDefault(stored, TRANSCRIPTION_BASE_URL, defaultTranscriptionBaseUrl),
            orDefault(stored, TRANSCRIPTION_MODEL, defaultTranscriptionModel));
    }

    /** Builds the masked view returned by {@code GET /api/settings/ai}. */
    @Transactional(readOnly = true)
    public AiSettingsView view() {
        EffectiveAiSettings e = effective();
        EffectivePreprocess p = effectivePreprocess();
        return new AiSettingsView(
            mask(e.chatApiKey()),
            e.chatBaseUrl(),
            e.chatModel(),
            mask(e.imageApiKey()),
            e.imageBaseUrl(),
            e.imageModel(),
            mask(e.videoApiKey()),
            e.videoBaseUrl(),
            e.videoModel(),
            mask(e.transcriptionApiKey()),
            e.transcriptionBaseUrl(),
            e.transcriptionModel(),
            p.workers(),
            p.maxWorkers(),
            p.prefetch(),
            p.aiMaxParallel(),
            p.aiThrottlePercent());
    }

    /** Resolves the currently effective preprocessing concurrency values. */
    @Transactional(readOnly = true)
    public EffectivePreprocess effectivePreprocess() {
        Map<String, String> stored = loadAll();
        int workers = orInt(stored, PREPROCESS_WORKERS, defaultWorkers);
        int maxWorkers = orInt(stored, PREPROCESS_MAX_WORKERS, defaultMaxWorkers);
        return new EffectivePreprocess(
            workers,
            maxWorkers,
            orInt(stored, PREPROCESS_PREFETCH, defaultPrefetch),
            orInt(stored, PREPROCESS_AI_MAX_PARALLEL, defaultAiMaxParallel),
            orInt(stored, PREPROCESS_AI_THROTTLE_PERCENT, defaultAiThrottlePercent));
    }

    /**
     * Computes the effective parallel AI call budget from the concurrency settings.
     * Mirrors the historical startup behavior: at most {@code aiThrottlePercent}% of the
     * worker ceiling, capped by {@code aiMaxParallel}, always at least 1.
     */
    @Transactional(readOnly = true)
    public int aiPermits() {
        EffectivePreprocess p = effectivePreprocess();
        int totalConsumers = Math.max(3, p.maxWorkers());
        int percentageLimit = (int) Math.max(1,
            Math.round(totalConsumers * p.aiThrottlePercent() / 100.0));
        return Math.max(1, Math.min(percentageLimit, p.aiMaxParallel()));
    }

    /**
     * Applies non-blank fields, ignoring unknown keys. Blank means "keep the current value";
     * use {@link #reset()} to fall back to the environment defaults.
     */
    @Transactional
    public AiSettingsView update(AiSettingsUpdateRequest request) {
        if (request == null) {
            return view();
        }
        store(CHAT_API_KEY, request.chatApiKey());
        store(CHAT_BASE_URL, request.chatBaseUrl());
        store(CHAT_MODEL, request.chatModel());
        store(IMAGE_API_KEY, request.imageApiKey());
        store(IMAGE_BASE_URL, request.imageBaseUrl());
        store(IMAGE_MODEL, request.imageModel());
        store(VIDEO_API_KEY, request.videoApiKey());
        store(VIDEO_BASE_URL, request.videoBaseUrl());
        store(VIDEO_MODEL, request.videoModel());
        store(TRANSCRIPTION_API_KEY, request.transcriptionApiKey());
        store(TRANSCRIPTION_BASE_URL, request.transcriptionBaseUrl());
        store(TRANSCRIPTION_MODEL, request.transcriptionModel());
        storeInt(PREPROCESS_WORKERS, request.workers(), 1, 32, "workers");
        storeInt(PREPROCESS_MAX_WORKERS, request.maxWorkers(), 1, 64, "maxWorkers");
        storeInt(PREPROCESS_PREFETCH, request.prefetch(), 1, 100, "prefetch");
        storeInt(PREPROCESS_AI_MAX_PARALLEL, request.aiMaxParallel(), 1, 64, "aiMaxParallel");
        storeInt(PREPROCESS_AI_THROTTLE_PERCENT, request.aiThrottlePercent(), 1, 100, "aiThrottlePercent");
        EffectivePreprocess p = effectivePreprocess();
        if (p.maxWorkers() < p.workers()) {
            throw new BadRequestException("maxWorkers must be greater than or equal to workers");
        }
        return view();
    }

    /** Deletes every override so the environment defaults apply again. */
    @Transactional
    public AiSettingsView reset() {
        repository.deleteAll();
        log.info("AI provider settings reset to environment defaults");
        return view();
    }

    public Duration chatTimeout() {
        return chatTimeout;
    }

    public Double chatTemperature() {
        return chatTemperature;
    }

    public Duration imageTimeout() {
        return imageTimeout;
    }

    public Duration videoTimeout() {
        return videoTimeout;
    }

    public Duration transcriptionTimeout() {
        return transcriptionTimeout;
    }

    private Map<String, String> loadAll() {
        Map<String, String> map = new HashMap<>();
        repository.findAll().forEach(s -> map.put(s.getKey(), s.getValue()));
        return map;
    }

    private static String orDefault(Map<String, String> stored, String key, String def) {
        String v = stored.get(key);
        return v == null ? def : v;
    }

    private static int orInt(Map<String, String> stored, String key, int def) {
        String v = stored.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void store(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        storeValue(key, value.trim());
        log.info("AI provider setting updated: {}", key);
    }

    private void storeInt(String key, Integer value, int min, int max, String field) {
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max);
        }
        storeValue(key, String.valueOf(value));
        log.info("Preprocessing setting updated: {}={}", key, value);
    }

    private void storeValue(String key, String value) {
        AppSetting setting = repository.findById(key).orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(key);
            return created;
        });
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        repository.save(setting);
    }

    /** Masks a secret for display; {@code null}/blank stays {@code null} (no key configured). */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String tail = value.length() <= 4 ? value : value.substring(value.length() - 4);
        return "••••" + tail;
    }
}
