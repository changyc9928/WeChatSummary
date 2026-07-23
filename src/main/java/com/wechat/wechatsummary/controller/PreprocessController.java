package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.entity.ImageSummaryEntity;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to manually kick off batch file ingestion,
 * system validation scanning, aborting tasks, querying execution progress,
 * and managing image summary description records.
 */
@Slf4j
@RestController
@RequestMapping("/api/preprocess")
public class PreprocessController {

    private final MediaProducerService producerService;
    private final TaskTaskCoordinatorService taskCoordinatorService;
    private final ImageProcessorService imageProcessorService;

    /**
     * Constructs a new PreprocessController with required orchestration and image processing services.
     */
    public PreprocessController(MediaProducerService producerService,
        TaskTaskCoordinatorService taskCoordinatorService,
        ImageProcessorService imageProcessorService) {
        this.producerService = producerService;
        this.taskCoordinatorService = taskCoordinatorService;
        this.imageProcessorService = imageProcessorService;
    }

    /**
     * Accepts structural preprocessing REST hooks for a target collection folder. Triggers
     * underlying file-tree sweeps and pushes extraction tasks into RabbitMQ queues.
     *
     * @param uuid unique transaction tracking token defining the active workspace storage location
     * @return confirmation text layout notifying message dispatch completion
     * @throws Exception if target directory parsing breaks down or broker exchanges are reachable
     */
    @PostMapping("/{uuid}")
    public String preprocess(@PathVariable String uuid) throws Exception {
        log.info(
            "REST endpoint invoked to initiate resource preprocessing tracking for session UUID: [{}]",
            uuid);

        producerService.preprocess(uuid);

        log.info("Successfully scheduled preprocessing orchestration tasks for session UUID: [{}]",
            uuid);
        return "Submitted preprocessing for " + uuid;
    }

    /**
     * Aborts the active asynchronous task orchestration tracking state and interrupts active threads.
     *
     * @param uuid unique transaction tracking token
     * @return HTTP 200 containing execution confirmation status
     */
    @PostMapping("/{uuid}/abort")
    public ResponseEntity<String> abortTask(@PathVariable String uuid) {
        log.info("REST endpoint invoked to ABORT processing for batch UUID: [{}]", uuid);
        boolean success = taskCoordinatorService.abortTask(uuid);

        if (success) {
            return ResponseEntity.ok("Successfully aborted preprocessing for batch " + uuid);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to abort preprocessing. No active threads found or task context missing for UUID: " + uuid);
        }
    }

    /**
     * Fetches detailed task tracking statistics intended for frontend progress bar rendering.
     *
     * @param uuid unique transaction tracking token
     * @return a TaskProgress payload mapping total, remaining, and active state (COMPLETED, RUNNING, IDLING)
     */
    @GetMapping("/{uuid}/progress")
    public ResponseEntity<TaskProgress> getProgress(@PathVariable String uuid) {
        log.debug("Fetching preprocessing progress state metrics for batch UUID: [{}]", uuid);
        TaskProgress progress = taskCoordinatorService.getTaskProgress(uuid);
        return ResponseEntity.ok(progress);
    }

    // =========================================================================
    // IMAGE SUMMARY MANAGEMENT ENDPOINTS
    // =========================================================================

    /**
     * Retrieves processed image description records scoped to a specific chat/session UUID with pagination.
     *
     * @param uuid unique target session/chat identifier
     * @param page zero-based page index (defaults to 0)
     * @param size the size of the page to be returned (defaults to 20)
     * @return Page of {@link ImageSummaryEntity} records associated with the specified UUID
     */
    @GetMapping("/images/summaries")
    public ResponseEntity<Page<ImageSummaryEntity>> getImageSummariesByUuid(
        @RequestParam("uuid") String uuid,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

        log.info("REST endpoint invoked to retrieve image description records for session UUID: [{}] (page: {}, size: {})",
            uuid, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<ImageSummaryEntity> summaries = imageProcessorService.getImageSummariesByUuid(uuid, pageable);

        return ResponseEntity.ok(summaries);
    }

    /**
     * Deletes a single image summary record by its unique ID (image hash).
     *
     * @param id target entity ID (image hash) to delete
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/images/summaries/{id}")
    public ResponseEntity<Void> deleteImageSummaryById(@PathVariable String id) {
        log.info("REST endpoint invoked to delete image summary record with ID: [{}]", id);
        imageProcessorService.deleteImageSummaryById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes multiple image summary records matching the provided list of IDs (image hashes).
     *
     * @param ids list of target entity IDs (image hashes) to delete
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/images/summaries")
    public ResponseEntity<Void> deleteImageSummariesByIds(@RequestBody List<String> ids) {
        log.info("REST endpoint invoked to batch delete [{}] image summary records.", ids != null ? ids.size() : 0);
        imageProcessorService.deleteImageSummariesByIds(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * Retrieves and streams the actual image file binary by its record ID (hash).
     *
     * @param id target entity ID (image hash)
     * @return ResponseEntity containing the raw file resource
     */
    @GetMapping("/images/{id}/file")
    public ResponseEntity<Resource> getImageFileById(@PathVariable String id) {
        log.info("REST endpoint invoked to fetch image file for ID: [{}]", id);

        return imageProcessorService.getImageFileById(id)
            .map(fileRes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileRes.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(fileRes.resource()))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}