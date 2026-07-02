package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.ZipExtractionService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class UploadController {

    private final ZipExtractionService zipExtractionService;

    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                .body("File is empty");
        }

        if (!Objects.requireNonNull(file.getOriginalFilename()).endsWith(".zip")) {
            return ResponseEntity.badRequest()
                .body("Only ZIP files allowed");
        }

        try {
            // 1. 接收 Service 层返回的 UUID
            String uuid = zipExtractionService.upload(file);

            // 2. 将 UUID 返回给前端（如果前端需要 JSON 格式，也可以后续包成一个 DTO）
            return ResponseEntity.ok(uuid);

        } catch (Exception e) {
            // 顺手改个小细节：上传/解压失败一般是比较严重的错误，
            // 建议用 log.error 记录异常堆栈，方便排查问题（比如权限不足、压缩包损坏等）
            log.error("Failed to process uploaded zip file", e);

            return ResponseEntity.internalServerError()
                .body("Upload failed: " + e.getMessage());
        }
    }
}