package com.wechat.wechatsummary.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.service.UserTokenService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class AuthInterceptorTest {

    private UserTokenService userTokenService;
    private AuthInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        userTokenService = mock(UserTokenService.class);
        interceptor = new AuthInterceptor(userTokenService, new ObjectMapper());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void rejectsRequestWithoutAuthorizationHeader() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/summary/sessions");

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
        assertNull(request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE));
    }

    @Test
    void rejectsRequestWithUnknownToken() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/summary/sessions");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer bogus");
        when(userTokenService.resolveUserId("bogus")).thenReturn(Optional.empty());

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(401, response.getStatus());
    }

    @Test
    void resolvesUserIdAndExposesItAsRequestAttribute() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/api/summary/sessions");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer good-token");
        when(userTokenService.resolveUserId("good-token")).thenReturn(Optional.of("user-42"));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals("user-42", request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE));
    }

    @Test
    void allowsCorsPreflightWithoutToken() throws Exception {
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/summary/sessions");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(userTokenService, never()).resolveUserId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exemptsBridgeLogWebhookPostButNotOtherMethods() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/tools/bridge/logs");
        assertTrue(interceptor.preHandle(request, response, new Object()));

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.setMethod("GET");
        request.setRequestURI("/api/tools/bridge/logs");

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }
}
