package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.AiSettingsUpdateRequest;
import com.wechat.wechatsummary.dto.AiSettingsView;
import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.service.AiSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-wide AI provider settings edited from the UI settings sidebar.
 *
 * <p>Secrets are only ever returned masked. Overrides apply to the next AI call; deleting
 * the settings falls back to the environment defaults.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final AiSettingsService aiSettingsService;

    @GetMapping("/ai")
    public ApiResponse<AiSettingsView> getAiSettings() {
        return ApiResponse.success(aiSettingsService.view());
    }

    @PutMapping("/ai")
    public ApiResponse<AiSettingsView> updateAiSettings(
        @RequestBody(required = false) AiSettingsUpdateRequest request) {
        log.info("AI provider settings update requested");
        return ApiResponse.success("AI settings saved", aiSettingsService.update(request));
    }

    @DeleteMapping("/ai")
    public ApiResponse<AiSettingsView> resetAiSettings() {
        log.info("AI provider settings reset requested");
        return ApiResponse.success("AI settings reset to server defaults", aiSettingsService.reset());
    }
}
