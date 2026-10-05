package com.wechat.wechatsummary.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wechat.wechatsummary.service.UserService;
import com.wechat.wechatsummary.service.UserTokenService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @TestConfiguration
    static class MockConfig {

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        UserTokenService userTokenService() {
            return mock(UserTokenService.class);
        }

        // The slice does not start Redis; caching infrastructure still needs a manager.
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserTokenService userTokenService;

    @BeforeEach
    void setUp() {
        doReturn(Optional.of("user-1")).when(userTokenService).resolveUserId("good-token");
    }

    @Test
    void loginIsPublicAndReturnsToken() throws Exception {
        doReturn("issued-token").when(userService).login("alice", "secret");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").value("issued-token"));
    }

    @Test
    void registerIsPublicAndReturnsToken() throws Exception {
        doReturn("issued-token").when(userService).register("bob", "secret");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").value("issued-token"));
    }

    @Test
    void logoutRequiresABearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void logoutRevokesThePresentedToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", nullValue()));

        verify(userService).logout("good-token");
    }
}
