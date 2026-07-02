package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.entity.ChatAnalysisTask;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import com.wechat.wechatsummary.service.ChatAnalysisCacheService;
import com.wechat.wechatsummary.service.ChatAnalysisService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class ChatAnalysisController {

    private final ChatAnalysisService chatAnalysisService;
    private final ChatAnalysisTaskRepository chatAnalysisTaskRepository;
    private final ChatAnalysisCacheService chatAnalysisCacheService;

    @PostMapping("/{uuid}")
    public ResponseEntity<?> startAnalysis(@PathVariable UUID uuid) {
        // 如果重新触发，先安全地把可能存在的旧缓存删掉
        chatAnalysisCacheService.evictCache(uuid);

        chatAnalysisTaskRepository.save(ChatAnalysisTask.builder()
            .id(uuid)
            .status("PROCESSING")
            .build());

        chatAnalysisService.analyzeChatLogAsync(uuid);

        return ResponseEntity.ok(Map.of(
            "status", "PROCESSING",
            "taskId", uuid
        ));
    }

    /**
     * 💡 享受 Redis 缓存红利的查询 Endpoint
     */
    @GetMapping("/status/{uuid}")
    public ResponseEntity<?> getAnalysisStatus(@PathVariable UUID uuid) {
        // 通过高并发抗压缓存服务获取任务元数据
        return chatAnalysisCacheService.getCachedTask(uuid)
            .map(task -> switch (task.getStatus()) {
                case "SUCCESS" -> {
                    try {
                        // 💡 核心改造：task.getResult() 此时在 DB 里是文本路径，在此处实时读取文件内容
                        Path txtPath = Paths.get(task.getResult());
                        if (!Files.exists(txtPath)) {
                            log.error("【文件丢失】数据库记录已是 SUCCESS，但未找到 TXT 报告文件: {}",
                                txtPath);
                            yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("status", "FAILED", "errorMessage",
                                    "分析已完成，但报告文件丢失。"));
                        }

                        String txtContent = Files.readString(txtPath, StandardCharsets.UTF_8);

                        // 💡 这一整段拼装好的结果，在第一次成功读取后会被 Spring Cache 整个打包塞进 Redis
                        // 下一次前端再来轮询，会直接命中缓存，连下面这几行代码和 IO 耗时都不会发生
                        yield ResponseEntity.ok(Map.of(
                            "status", "SUCCESS",
                            "result", txtContent
                        ));
                    } catch (Exception e) {
                        log.error("读取本地 TXT 报告文件失败, UUID: {}", uuid, e);
                        yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(
                                Map.of("status", "FAILED", "errorMessage", "读取分析报告文件失败"));
                    }
                }
                case "FAILED" -> ResponseEntity.ok(Map.of(
                    "status", "FAILED",
                    "errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : "未知错误"
                ));
                default -> ResponseEntity.ok(Map.of(
                    "status", "PROCESSING",
                    "message", "大模型正在努力吃瓜分析中，请稍后..."
                ));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "未找到对应的分析任务。")));
    }
}