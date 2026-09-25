package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.AiSettingsUpdateRequest;
import com.wechat.wechatsummary.dto.AiSettingsView;
import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.service.AiSettingsService;
import com.wechat.wechatsummary.service.PreprocessConcurrencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-wide AI provider and preprocessing settings edited from the UI settings sidebar.
 *
 * <p>Secrets are only ever returned masked. Overrides apply live: provider clients rebuild
 * on the next AI call and consumer concurrency is pushed into the running RabbitMQ
 * containers. Deleting the settings falls back to the environment defaults.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final AiSettingsService aiSettingsService;
    private final PreprocessConcurrencyService preprocessConcurrencyService;

    @GetMapping("/ai")
    public ApiResponse<AiSettingsView> getAiSettings() {
        return ApiResponse.success(aiSettingsService.view());
    }

    @PutMapping("/ai")
    public ApiResponse<AiSettingsView> updateAiSettings(
        @RequestBody(required = false) AiSettingsUpdateRequest request) {
        log.info("AI provider settings update requested");
        AiSettingsView view = aiSettingsService.update(request);
        applyConcurrency();
        return ApiResponse.success("AI settings saved", view);
    }

    @DeleteMapping("/ai")
    public ApiResponse<AiSettingsView> resetAiSettings() {
        log.info("AI provider settings reset requested");
        AiSettingsView view = aiSettingsService.reset();
        applyConcurrency();
        return ApiResponse.success("AI settings reset to server defaults", view);
    }

    /**
     * Pushes concurrency values into the running consumers. The settings themselves are
     * already committed at this point, so a failure here only delays (never loses) the
     * effect: containers pick the values up on restart/scale.
     */
    private void applyConcurrency() {
        try {
            preprocessConcurrencyService.apply();
        } catch (Exception e) {
            log.warn("Settings saved but live consumer scaling failed; values apply on restart", e);
        }
    }
}
