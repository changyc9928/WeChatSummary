package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.AuthRequest;
import com.wechat.wechatsummary.dto.AuthResponse;
import com.wechat.wechatsummary.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        String uuid = userService.register(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(new AuthResponse(uuid));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        String uuid = userService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(new AuthResponse(uuid));
    }
}