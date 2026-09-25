package com.wechat.wechatsummary.service;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Service;

/**
 * Builds AI clients from the currently effective provider settings.
 *
 * <p>Clients are cached and transparently rebuilt the first time a call observes changed
 * settings, so keys/endpoints/models edited in the settings sidebar take effect on the next
 * AI call without restarting the backend.
 */
@Service
@Slf4j
public class AiModelFactory {

    private final AiSettingsService settingsService;

    private volatile Cached cached;

    public AiModelFactory(AiSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public ChatClient chatClient() {
        return current().chat;
    }

    public ChatClient imageChatClient() {
        return current().image;
    }

    public ChatClient videoChatClient() {
        return current().video;
    }

    public OpenAiAudioTranscriptionModel transcriptionModel() {
        return current().transcription;
    }

    private Cached current() {
        AiSettingsService.EffectiveAiSettings effective = settingsService.effective();
        Cached snapshot = cached;
        if (snapshot != null && snapshot.settings.equals(effective)) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cached;
            if (snapshot != null && snapshot.settings.equals(effective)) {
                return snapshot;
            }
            Cached rebuilt = build(effective);
            cached = rebuilt;
            return rebuilt;
        }
    }

    private Cached build(AiSettingsService.EffectiveAiSettings e) {
        log.info("Rebuilding AI clients (chat model={}, image model={}, video model={}, transcription model={})",
            e.chatModel(), e.imageModel(), e.videoModel(), e.transcriptionModel());

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .model(e.chatModel())
                .baseUrl(e.chatBaseUrl())
                .apiKey(e.chatApiKey())
                .temperature(settingsService.chatTemperature())
                .timeout(settingsService.chatTimeout())
                .build())
            .build();

        OpenAiChatModel imageModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .model(e.imageModel())
                .baseUrl(e.imageBaseUrl())
                .apiKey(e.imageApiKey())
                .timeout(settingsService.imageTimeout())
                .build())
            .build();

        OpenAiChatModel videoModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .model(e.videoModel())
                .baseUrl(e.videoBaseUrl())
                .apiKey(e.videoApiKey())
                .timeout(settingsService.videoTimeout())
                .build())
            .build();

        // An empty key enables no-auth mode: Spring AI strips the Authorization
        // header, which suits keyless local servers (self-hosted Whisper, Ollama...).
        OpenAIClient whisperClient = OpenAiSetup.setupSyncClient(
            e.transcriptionBaseUrl(), e.transcriptionApiKey(), null, null, null, null,
            false, false, e.transcriptionModel(), settingsService.transcriptionTimeout(), 3, null, null,
            ObservationRegistry.NOOP, null, List.of());
        OpenAiAudioTranscriptionModel transcription = OpenAiAudioTranscriptionModel.builder()
            .openAiClient(whisperClient)
            .options(OpenAiAudioTranscriptionOptions.builder()
                .model(e.transcriptionModel())
                .timeout(settingsService.transcriptionTimeout())
                .build())
            .build();

        return new Cached(e,
            ChatClient.builder(chatModel).build(),
            ChatClient.builder(imageModel).build(),
            ChatClient.builder(videoModel).build(),
            transcription);
    }

    private record Cached(
        AiSettingsService.EffectiveAiSettings settings,
        ChatClient chat,
        ChatClient image,
        ChatClient video,
        OpenAiAudioTranscriptionModel transcription) {
    }
}
