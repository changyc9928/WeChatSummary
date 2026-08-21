package com.wechat.wechatsummary.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.service.BridgeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives log lines streamed from the local {@code bridge.exe} sidecar
 * (started with {@code --log-webhook http://<host>:8080/api/tools/bridge/logs})
 * and serves them back to the frontend log viewer. The same endpoint also
 * appends everything to {@code bridge-logs.log} in the tools dir.
 */
@RestController
@RequestMapping("/api/tools/bridge/logs")
@RequiredArgsConstructor
@Tag(name = "bridge-log-controller", description = "Bridge log streaming (webhook ingest + viewer)")
public class BridgeLogController {

    private final BridgeLogService bridgeLogService;

    /** Response for GET: lines after the cursor plus the new cursor. */
    public record LogsResponse(List<BridgeLogService.StoredLine> lines, long next) {
    }

    @Operation(summary = "Ingest log lines POSTed by the bridge webhook")
    @PostMapping
    public ApiResponse<Integer> ingest(@RequestBody(required = false) JsonNode body) {
        if (body == null) {
            return ApiResponse.success(0);
        }
        // The bridge webhook sends {"lines":[...]}; accept a raw array too.
        JsonNode arrayNode = body.isArray() ? body : body.get("lines");
        if (arrayNode == null || !arrayNode.isArray()) {
            throw new IllegalArgumentException("expected a JSON array or {\"lines\": [...]}");
        }
        // Jackson 3 (tools.jackson) ignores unknown properties by default, so
        // the bridge's extra "seq" field is simply skipped.
        ObjectMapper mapper = new ObjectMapper();
        List<BridgeLogService.BridgeLogLine> lines = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            lines.add(mapper.convertValue(n, BridgeLogService.BridgeLogLine.class));
        }
        return ApiResponse.success(bridgeLogService.ingest(lines));
    }

    @Operation(summary = "Return stored bridge log lines after the given cursor")
    @GetMapping
    public ApiResponse<LogsResponse> since(@RequestParam(defaultValue = "0") long after) {
        List<BridgeLogService.StoredLine> lines = bridgeLogService.since(after);
        return ApiResponse.success(new LogsResponse(lines, bridgeLogService.cursor()));
    }

    @Operation(summary = "Clear the in-memory bridge log ring")
    @DeleteMapping
    public ApiResponse<Void> clear() {
        bridgeLogService.clear();
        return ApiResponse.success(null);
    }
}
