package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.dto.AuthRequest;
import com.wechat.wechatsummary.dto.AuthResponse;
import com.wechat.wechatsummary.security.AuthInterceptor;
import com.wechat.wechatsummary.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody AuthRequest request) {
        String token = userService.register(request.getUsername(), request.getPassword());
        return ApiResponse.success("Registration successful", new AuthResponse(token));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request) {
        String token = userService.login(request.getUsername(), request.getPassword());
        return ApiResponse.success("Login successful", new AuthResponse(token));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        if (authorization != null && authorization.startsWith(AuthInterceptor.BEARER_PREFIX)) {
            userService.logout(authorization.substring(AuthInterceptor.BEARER_PREFIX.length()).trim());
        }
        return ApiResponse.success("Logged out", null);
    }
}
