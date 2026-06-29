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

            zipExtractionService.upload(file);

            return ResponseEntity.ok(
                "Extracted files"
            );

        } catch (Exception e) {
            log.debug(e.getMessage());

            return ResponseEntity.internalServerError()
                .body("Upload failed: " + e.getMessage());
        }
    }
}
