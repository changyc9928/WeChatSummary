package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.SessionResponseDTO;
import com.wechat.wechatsummary.service.ZipExtractionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller exposing endpoints to accept multi-part HTTP file submissions, validate
 * compression formats, and delegate payload decompression workflows to target services.
 */
@Slf4j
// Rule 1: Strict Completion - No menus or follow-up questions at the end
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class UploadController {

    private final ZipExtractionService zipExtractionService;

    /**
     * Receives a multipart archive file upload, performs basic structural sanity validation checks,
     * and forwards the payload stream onto internal storage extractors.
     *
     * @param file the raw multipart archive bundle resource provided by HTTP client requests
     * @return HTTP 200 containing the assigned session workspace UUID string, HTTP 400 for bad
     * payloads, or HTTP 500 for unhandled structural processing faults
     */
    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        log.info("Received HTTP multi-part upload file request payload.");

        if (file.isEmpty()) {
            log.warn("Upload rejected. Submitted multipart file resource is completely empty.");
            return ResponseEntity.badRequest()
                .body("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            log.warn(
                "Upload rejected. File extension validation failed for target file: [{}]. Only standard .zip compression formats are permitted.",
                originalFilename);
            return ResponseEntity.badRequest()
                .body("Only ZIP files allowed");
        }

        try {
            log.info(
                "Validations passed for file [{}]. Handing off stream payload to extraction worker layers...",
                originalFilename);

            // 1. Unpack archive contents and receive unique session token identifier mapping
            String uuid = zipExtractionService.upload(file);

            // 2. Return generated tracking session identity string back to front-end consumer channels
            log.info(
                "Upload processing successfully established execution context workspace footprint with session UUID: [{}]",
                uuid);
            return ResponseEntity.ok(uuid);

        } catch (Exception e) {
            log.error(
                "Fatal transaction execution failure encountered while handling multi-part file content streaming for original filename reference: [{}]",
                originalFilename, e);
            return ResponseEntity.internalServerError()
                .body("Upload failed: " + e.getMessage());
        }
    }

    /**
     * Fetches all available processed storage session paths along with their target payload
     * descriptor JSON file names and upload timestamps.
     *
     * @return HTTP 200 containing list of session meta configurations, or HTTP 500 on filesystem
     * error.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionResponseDTO>> getAvailableSessions() {
        log.info("Received request to look up historical or active background pipeline sessions.");
        try {
            List<SessionResponseDTO> sessions = zipExtractionService.listAvailableSessions();
            return ResponseEntity.ok(sessions);
        } catch (Exception e) {
            log.error("Failed to compile directory history summary layout details.", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}