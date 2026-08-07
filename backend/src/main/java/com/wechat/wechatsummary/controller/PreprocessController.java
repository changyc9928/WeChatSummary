package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.ApiResponse;
import com.wechat.wechatsummary.dto.TaskAckResponse;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.exception.BadRequestException;
import com.wechat.wechatsummary.exception.BusinessException;
import com.wechat.wechatsummary.service.AudioProcessorService;
import com.wechat.wechatsummary.service.ImageProcessorService;
import com.wechat.wechatsummary.service.MediaProducerService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to manually kick off batch file ingestion, system validation
 * scanning, aborting tasks, querying execution progress, and managing image/audio summary
 * description records isolated per user UUID.
 */
@Slf4j
@RestController
@RequestMapping("/api/preprocess")
public class PreprocessController {

    private final MediaProducerService producerService;
    private final TaskTaskCoordinatorService taskCoordinatorService;
    private final ImageProcessorService imageProcessorService;
    private final AudioProcessorService audioProcessorService;

    public PreprocessController(MediaProducerService producerService,
        TaskTaskCoordinatorService taskCoordinatorService,
        ImageProcessorService imageProcessorService,
        AudioProcessorService audioProcessorService) {
        this.producerService = producerService;
        this.taskCoordinatorService = taskCoordinatorService;
        this.imageProcessorService = imageProcessorService;
        this.audioProcessorService = audioProcessorService;
    }

    @PostMapping("/{uuid}")
    public ApiResponse<TaskAckResponse> preprocess(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) {
        log.info(
            "REST endpoint invoked to initiate resource preprocessing tracking for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);

        try {
            producerService.preprocess(userId, uuid);
        } catch (IOException e) {
            log.error(
                "Failed to preprocess session UUID: [{}] for user UUID: [{}]",
                uuid, userId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to preprocess session");
        }

        log.info(
            "Successfully scheduled preprocessing orchestration tasks for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);
        return ApiResponse.success("Preprocessing started",
            new TaskAckResponse(uuid, null, "Preprocessing started"));
    }

    @PostMapping("/{uuid}/abort")
    public ApiResponse<TaskAckResponse> abortTask(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) {
        log.info(
            "REST endpoint invoked to ABORT processing for user UUID: [{}] and batch UUID: [{}]",
            userId, uuid);
        boolean success = taskCoordinatorService.abortTask(uuid);

        if (success) {
            return ApiResponse.success("Successfully aborted preprocessing",
                new TaskAckResponse(uuid, null, "Successfully aborted preprocessing"));
        }
        throw new BadRequestException(
            "Failed to abort preprocessing. No active threads found or task context missing for UUID: "
                + uuid);
    }

    @GetMapping("/{uuid}/progress")
    public ApiResponse<TaskProgress> getProgress(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) {
        log.debug(
            "Fetching preprocessing progress state metrics for user UUID: [{}] and batch UUID: [{}]",
            userId, uuid);
        TaskProgress progress = taskCoordinatorService.getTaskProgress(uuid, userId);
        return ApiResponse.success(progress);
    }

    // =========================================================================
    // IMAGE SUMMARY MANAGEMENT ENDPOINTS
    // =========================================================================

    @GetMapping("/images/summaries")
    public ApiResponse<Page<ImageSummaryEntity>> getImageSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

        log.info(
            "REST endpoint invoked to retrieve image summaries for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);
        Pageable pageable = PageRequest.of(page, size);
        Page<ImageSummaryEntity> summaries = imageProcessorService.getImageSummariesByUuid(uuid,
            pageable);

        return ApiResponse.success(summaries);
    }

    @DeleteMapping("/images/summaries/{id}")
    public ApiResponse<Void> deleteImageSummaryById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info(
            "REST endpoint invoked to delete image summary record with ID: [{}] for user UUID: [{}]",
            id, userId);
        imageProcessorService.deleteImageSummaryById(id);
        return ApiResponse.success("Image summary deleted", null);
    }

    @DeleteMapping("/images/summaries")
    public ApiResponse<Void> deleteImageSummariesByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info(
            "REST endpoint invoked to batch delete [{}] image summary records for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        imageProcessorService.deleteImageSummariesByIds(ids);
        return ApiResponse.success("Image summaries deleted", null);
    }

    @DeleteMapping("/images/summaries/all")
    public ApiResponse<Void> deleteAllImageSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid) {
        log.info(
            "REST endpoint invoked to delete ALL image summary records for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);
        imageProcessorService.deleteAllImageSummariesByUuid(uuid);
        return ApiResponse.success("All image summaries deleted", null);
    }

    @GetMapping("/images/{id}/file")
    public ResponseEntity<Resource> getImageFileById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to fetch image file for ID: [{}] and user UUID: [{}]", id,
            userId);

        return imageProcessorService.getImageFileById(id)
            .map(fileRes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileRes.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(fileRes.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =========================================================================
    // AUDIO SUMMARY MANAGEMENT ENDPOINTS
    // =========================================================================

    @GetMapping("/audios/summaries")
    public ApiResponse<Page<AudioSummary>> getAudioSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

        log.info(
            "REST endpoint invoked to retrieve audio summary records for user UUID: [{}] and session UUID: [{}] (page: {}, size: {})",
            userId, uuid, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<AudioSummary> summaries = audioProcessorService.getAudioSummariesByUuid(uuid,
            pageable);

        return ApiResponse.success(summaries);
    }

    /**
     * Clears ONLY the summary text for a specific audio record (keeps transcript intact).
     *
     * @param userId caller user UUID header
     * @param id     target entity ID (audio hash)
     * @return unified envelope wrapping a successful deletion operation
     */
    @DeleteMapping("/audios/summaries/{id}/text")
    public ApiResponse<Void> clearAudioSummaryTextById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info(
            "REST endpoint invoked to clear audio summary text for ID: [{}] and user UUID: [{}]",
            id, userId);
        audioProcessorService.clearAudioSummaryTextById(id);
        return ApiResponse.success("Audio summary text cleared", null);
    }

    /**
     * Clears ONLY the summary text for multiple audio records (keeps transcripts intact).
     *
     * @param userId caller user UUID header
     * @param ids    list of target entity IDs (audio hashes)
     * @return unified envelope containing a successful batch clear operation
     */
    @DeleteMapping("/audios/summaries/text")
    public ApiResponse<Void> clearAudioSummaryTextsByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info(
            "REST endpoint invoked to batch clear [{}] audio summary texts for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        audioProcessorService.clearAudioSummaryTextsByIds(ids);
        return ApiResponse.success("Audio summary texts cleared", null);
    }

    @DeleteMapping("/audios/summaries/{id}")
    public ApiResponse<Void> deleteAudioSummaryById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info(
            "REST endpoint invoked to delete full audio record with ID: [{}] for user UUID: [{}]",
            id, userId);
        audioProcessorService.deleteAudioSummaryById(id);
        return ApiResponse.success("Audio summary deleted", null);
    }

    @DeleteMapping("/audios/summaries")
    public ApiResponse<Void> deleteAudioSummariesByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info(
            "REST endpoint invoked to batch delete [{}] full audio records for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        audioProcessorService.deleteAudioSummariesByIds(ids);
        return ApiResponse.success("Audio summaries deleted", null);
    }

    @DeleteMapping("/audios/summaries/all")
    public ApiResponse<Void> deleteAllAudioSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid) {
        log.info(
            "REST endpoint invoked to delete ALL full audio records for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);
        audioProcessorService.deleteAllAudioSummariesByUuid(uuid);
        return ApiResponse.success("All audio summaries deleted", null);
    }

    @GetMapping("/audios/{id}/file")
    public ResponseEntity<Resource> getAudioFileById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to fetch audio file for ID: [{}] and user UUID: [{}]", id,
            userId);

        return audioProcessorService.getAudioFileById(id)
            .map(fileObj -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileObj.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(fileObj.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}