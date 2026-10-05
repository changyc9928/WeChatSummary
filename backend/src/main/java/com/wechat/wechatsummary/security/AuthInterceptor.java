package com.wechat.wechatsummary.security;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.service.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Universal authentication interceptor: resolves the user UUID from the {@code Authorization:
 * Bearer <token>} header via Redis and exposes it to controllers as the {@code userId} request
 * attribute. Controllers therefore never receive (or trust) a user id from the frontend.
 *
 * <p>Login/register are excluded at registration time; the local bridge's log webhook is exempted
 * here for POST only because {@code bridge.exe} has no user credentials.
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Request attribute holding the authenticated user UUID. */
    public static final String USER_ID_ATTRIBUTE = "userId";

    public static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> PUBLIC_POST_PATHS = Set.of("/api/tools/bridge/logs");

    private final UserTokenService userTokenService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
        // CORS preflight carries no credentials and must reach the CORS handling.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // Machine-to-machine log mirroring: bridge.exe cannot hold a user token.
        if (HttpMethod.POST.matches(request.getMethod())
            && PUBLIC_POST_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return reject(response);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return reject(response);
        }
        Optional<String> userId = userTokenService.resolveUserId(token);
        if (userId.isEmpty()) {
            return reject(response);
        }

        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        return true;
    }

    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "Authentication required")));
        return false;
    }
}
