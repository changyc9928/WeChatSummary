package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.service.BridgeToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Distributes the Windows {@code bridge.exe} sidecar to end users. The
 * browser cannot scan OS memory itself; this endpoint hands them the native
 * bridge binary so the frontend can drive it over loopback HTTP.
 */
@RestController
@RequestMapping("/api/tools/bridge")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "tool-controller", description = "Distributable local tools (bridge.exe)")
public class ToolController {

    private final BridgeToolService bridgeToolService;

    @Operation(summary = "Metadata for the bridge.exe download (present, size, SHA-256)")
    @GetMapping("/meta")
    public ApiResponse<BridgeToolService.BridgeToolMeta> meta() {
        return ApiResponse.success(bridgeToolService.meta());
    }

    @Operation(summary = "Download bridge.exe (Windows sidecar); 404 with build instructions when absent")
    @GetMapping("/download")
    public ResponseEntity<?> download() {
        return bridgeToolService.download();
    }
}