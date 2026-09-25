package com.wechat.wechatsummary.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.dto.AiSettingsUpdateRequest;
import com.wechat.wechatsummary.dto.AiSettingsView;
import com.wechat.wechatsummary.exception.BadRequestException;
import com.wechat.wechatsummary.service.AiSettingsService;
import com.wechat.wechatsummary.service.PreprocessConcurrencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @TestConfiguration
    static class MockConfig {
        @Bean
        AiSettingsService aiSettingsService() {
            return mock(AiSettingsService.class);
        }

        @Bean
        PreprocessConcurrencyService preprocessConcurrencyService() {
            return mock(PreprocessConcurrencyService.class);
        }

        // The slice does not start Redis; caching infrastructure still needs a manager.
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AiSettingsService aiSettingsService;

    @Autowired
    private PreprocessConcurrencyService preprocessConcurrencyService;

    private static AiSettingsView view() {
        return new AiSettingsView(
            "••••abcd", "https://chat/v1", "model-a",
            null, "https://image/v1", "model-b",
            null, "https://video/v1", "model-c",
            null, "http://whisper/v1", "model-d",
            3, 10, 5, 10, 10);
    }

    @Test
    void getReturnsMaskedView() throws Exception {
        doReturn(view()).when(aiSettingsService).view();

        mockMvc.perform(get("/api/settings/ai"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.chatApiKey").value("••••abcd"))
            .andExpect(jsonPath("$.data.imageApiKey", nullValue()))
            .andExpect(jsonPath("$.data.workers").value(3));
    }

    @Test
    void putSavesAndAppliesConcurrencyLive() throws Exception {
        doReturn(view()).when(aiSettingsService).update(any());
        // ignore the boot-time ApplicationReadyEvent call; count request-driven calls only
        clearInvocations(preprocessConcurrencyService);

        String body = objectMapper.writeValueAsString(new AiSettingsUpdateRequest(
            "sk-new", null, null,
            null, null, null,
            null, null, null,
            null, null, null,
            6, null, null, null, null));

        mockMvc.perform(put("/api/settings/ai")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("AI settings saved"))
            .andExpect(jsonPath("$.data.workers").value(3));

        verify(preprocessConcurrencyService).apply();
    }

    @Test
    void putValidationFailureSurfacesAs400() throws Exception {
        doThrow(new BadRequestException("maxWorkers must be greater than or equal to workers"))
            .when(aiSettingsService).update(any());

        mockMvc.perform(put("/api/settings/ai")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workers\":8,\"maxWorkers\":2}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message")
                .value("maxWorkers must be greater than or equal to workers"));
    }

    @Test
    void deleteResetsAndAppliesConcurrencyLive() throws Exception {
        doReturn(view()).when(aiSettingsService).reset();
        // ignore the boot-time ApplicationReadyEvent call; count request-driven calls only
        clearInvocations(preprocessConcurrencyService);

        mockMvc.perform(delete("/api/settings/ai"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("AI settings reset to server defaults"));

        verify(preprocessConcurrencyService).apply();
    }
}
