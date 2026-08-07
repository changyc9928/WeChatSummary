package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.dto.SessionResponseDTO;
import com.wechat.wechatsummary.dto.UploadSessionResponse;
import com.wechat.wechatsummary.exception.BadRequestException;
import com.wechat.wechatsummary.exception.BusinessException;
import com.wechat.wechatsummary.service.ZipExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller exposing endpoints to accept multi-part HTTP file submissions, validate
 * compression formats, and delegate payload decompression workflows to target services.
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class UploadController {

    private final ZipExtractionService zipExtractionService;

    /**
     * Marker schema so springdoc renders the multipart file field as binary.
     */
    @Schema(type = "string", format = "binary")
    public static class FileBinarySchema {
    }

    @Operation(
        requestBody = @RequestBody(
            description = "ZIP archive to ingest",
            required = true,
            content = @Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = @Schema(
                    type = "object",
                    properties = @StringToClassMapItem(key = "file", value = FileBinarySchema.class)
                )
            )
        )
    )
    @PostMapping("/upload")
    public ApiResponse<UploadSessionResponse> upload(
        @RequestHeader("X-User-Id") String userId,
        @RequestPart("file") MultipartFile file) {
        log.info("Received HTTP multi-part upload file request payload for user UUID: [{}]",
            userId);

        if (file.isEmpty()) {
            log.warn("Upload rejected. Submitted multipart file resource is completely empty.");
            throw new BadRequestException("File is empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            log.warn(
                "Upload rejected. File extension validation failed for target file: [{}]. Only standard .zip compression formats are permitted.",
                originalFilename);
            throw new BadRequestException("Only ZIP files allowed");
        }

        try {
            log.info(
                "Validations passed for file [{}]. Handing off stream payload to extraction worker layers...",
                originalFilename);

            String sessionId = zipExtractionService.upload(userId, file);

            log.info(
                "Upload processing successfully established execution context workspace footprint with session UUID: [{}] for user UUID: [{}]",
                sessionId, userId);
            return ApiResponse.success("Upload successful",
                new UploadSessionResponse(sessionId));
        } catch (IOException e) {
            log.error(
                "Fatal transaction execution failure encountered while handling multi-part file content streaming for original filename reference: [{}]",
                originalFilename, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Upload processing failed");
        }
    }

    /**
     * Fetches all available processed storage session paths scoped strictly to the calling user
     * UUID.
     *
     * @param userId the user's UUID primary key passed via authorization headers
     * @return unified envelope containing list of session meta configurations
     */
    @GetMapping("/sessions")
    public ApiResponse<List<SessionResponseDTO>> getAvailableSessions(
        @RequestHeader("X-User-Id") String userId) {
        log.info(
            "Received request to look up historical or active background pipeline sessions for user UUID: [{}]",
            userId);
        try {
            List<SessionResponseDTO> sessions = zipExtractionService.listAvailableSessions(userId);
            return ApiResponse.success(sessions);
        } catch (IOException e) {
            log.error(
                "Failed to compile directory history summary layout details for user UUID: [{}]",
                userId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to list available sessions");
        }
    }
}