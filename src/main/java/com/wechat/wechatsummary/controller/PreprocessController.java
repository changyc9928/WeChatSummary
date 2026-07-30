package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.entity.AudioSummary;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
import com.wechat.wechatsummary.service.AudioProcessorService;
import com.wechat.wechatsummary.service.ImageProcessorService;
import com.wechat.wechatsummary.service.MediaProducerService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
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
    public String preprocess(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) throws Exception {
        log.info(
            "REST endpoint invoked to initiate resource preprocessing tracking for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);

        producerService.preprocess(userId, uuid);

        log.info("Successfully scheduled preprocessing orchestration tasks for user UUID: [{}] and session UUID: [{}]",
            userId, uuid);
        return "Submitted preprocessing for session " + uuid;
    }

    @PostMapping("/{uuid}/abort")
    public ResponseEntity<String> abortTask(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) {
        log.info("REST endpoint invoked to ABORT processing for user UUID: [{}] and batch UUID: [{}]", userId, uuid);
        boolean success = taskCoordinatorService.abortTask(uuid);

        if (success) {
            return ResponseEntity.ok("Successfully aborted preprocessing for batch " + uuid);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    "Failed to abort preprocessing. No active threads found or task context missing for UUID: "
                        + uuid);
        }
    }

    @GetMapping("/{uuid}/progress")
    public ResponseEntity<TaskProgress> getProgress(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String uuid) {
        log.debug("Fetching preprocessing progress state metrics for user UUID: [{}] and batch UUID: [{}]", userId, uuid);
        TaskProgress progress = taskCoordinatorService.getTaskProgress(uuid, userId);
        return ResponseEntity.ok(progress);
    }

    // =========================================================================
    // IMAGE SUMMARY MANAGEMENT ENDPOINTS
    // =========================================================================

    @GetMapping("/images/summaries")
    public ResponseEntity<Page<ImageSummaryEntity>> getImageSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

        log.info("REST endpoint invoked to retrieve image summaries for user UUID: [{}] and session UUID: [{}]", userId, uuid);
        Pageable pageable = PageRequest.of(page, size);
        Page<ImageSummaryEntity> summaries = imageProcessorService.getImageSummariesByUuid(uuid,
            pageable);

        return ResponseEntity.ok(summaries);
    }

    @DeleteMapping("/images/summaries/{id}")
    public ResponseEntity<Void> deleteImageSummaryById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to delete image summary record with ID: [{}] for user UUID: [{}]", id, userId);
        imageProcessorService.deleteImageSummaryById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/images/summaries")
    public ResponseEntity<Void> deleteImageSummariesByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info("REST endpoint invoked to batch delete [{}] image summary records for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        imageProcessorService.deleteImageSummariesByIds(ids);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/images/summaries/all")
    public ResponseEntity<Void> deleteAllImageSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid) {
        log.info("REST endpoint invoked to delete ALL image summary records for user UUID: [{}] and session UUID: [{}]", userId, uuid);
        imageProcessorService.deleteAllImageSummariesByUuid(uuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/images/{id}/file")
    public ResponseEntity<Resource> getImageFileById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to fetch image file for ID: [{}] and user UUID: [{}]", id, userId);

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
    public ResponseEntity<Page<AudioSummary>> getAudioSummariesByUuid(
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

        return ResponseEntity.ok(summaries);
    }

    /**
     * Clears ONLY the summary text for a specific audio record (keeps transcript intact).
     *
     * @param userId caller user UUID header
     * @param id     target entity ID (audio hash)
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/audios/summaries/{id}/text")
    public ResponseEntity<Void> clearAudioSummaryTextById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to clear audio summary text for ID: [{}] and user UUID: [{}]", id, userId);
        audioProcessorService.clearAudioSummaryTextById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears ONLY the summary text for multiple audio records (keeps transcripts intact).
     *
     * @param userId caller user UUID header
     * @param ids    list of target entity IDs (audio hashes)
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/audios/summaries/text")
    public ResponseEntity<Void> clearAudioSummaryTextsByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info("REST endpoint invoked to batch clear [{}] audio summary texts for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        audioProcessorService.clearAudioSummaryTextsByIds(ids);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/audios/summaries/{id}")
    public ResponseEntity<Void> deleteAudioSummaryById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to delete full audio record with ID: [{}] for user UUID: [{}]", id, userId);
        audioProcessorService.deleteAudioSummaryById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/audios/summaries")
    public ResponseEntity<Void> deleteAudioSummariesByIds(
        @RequestHeader("X-User-Id") String userId,
        @RequestBody List<String> ids) {
        log.info("REST endpoint invoked to batch delete [{}] full audio records for user UUID: [{}]",
            ids != null ? ids.size() : 0, userId);
        audioProcessorService.deleteAudioSummariesByIds(ids);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/audios/summaries/all")
    public ResponseEntity<Void> deleteAllAudioSummariesByUuid(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam("uuid") String uuid) {
        log.info("REST endpoint invoked to delete ALL full audio records for user UUID: [{}] and session UUID: [{}]", userId, uuid);
        audioProcessorService.deleteAllAudioSummariesByUuid(uuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audios/{id}/file")
    public ResponseEntity<Resource> getAudioFileById(
        @RequestHeader("X-User-Id") String userId,
        @PathVariable String id) {
        log.info("REST endpoint invoked to fetch audio file for ID: [{}] and user UUID: [{}]", id, userId);

        return audioProcessorService.getAudioFileById(id)
            .map(fileRes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileRes.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(fileRes.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}