package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.service.MediaProducerService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to manually kick off batch file ingestion, system validation
 * scanning, and individual multi-media parsing message production loops.
 */
@Slf4j
@RestController
@RequestMapping("/api/preprocess")
public class PreprocessController {

    private final MediaProducerService producerService;
    private final TaskTaskCoordinatorService taskCoordinatorService;

    /**
     * Constructs a new PreprocessController with the required streaming orchestrator service.
     */
    public PreprocessController(MediaProducerService producerService,
        TaskTaskCoordinatorService taskCoordinatorService) {
        this.producerService = producerService;
        this.taskCoordinatorService = taskCoordinatorService;
    }

    /**
     * Accepts structural preprocessing REST hooks for a target collection folder. Triggers
     * underlying file-tree sweeps and pushes extraction tasks into RabbitMQ queues.
     *
     * @param uuid unique transaction tracking token defining the active workspace storage location
     * @return confirmation text layout notifying message dispatch completion
     * @throws Exception if target directory parsing breaks down or broker exchanges are
     *                   unreachable
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
     * Pauses the active asynchronous task orchestration tracking state. Subsequent worker threads
     * reading this key will automatically drop their active payloads.
     *
     * @param uuid unique transaction tracking token
     * @return HTTP 200 containing execution confirmation status
     */
    @PostMapping("/{uuid}/pause")
    public ResponseEntity<String> pauseTask(@PathVariable String uuid) {
        log.info("REST endpoint invoked to PAUSE processing for batch UUID: [{}]", uuid);
        boolean success = taskCoordinatorService.pauseTask(uuid);

        if (success) {
            return ResponseEntity.ok("Successfully paused preprocessing for batch " + uuid);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    "Failed to pause preprocessing. Task context may be complete, missing, or already paused.");
        }
    }

    /**
     * Wipes the existing tracking metrics, resets status back to processing, and physically
     * re-queues all storage items back into RabbitMQ.
     *
     * @param uuid unique transaction tracking token
     * @return HTTP 200 containing execution confirmation status
     */
    @PostMapping("/{uuid}/start-over")
    public ResponseEntity<String> startOverTask(@PathVariable String uuid) {
        log.info("REST endpoint invoked to START OVER processing for batch UUID: [{}]", uuid);
        try {
            producerService.startOver(uuid);
            return ResponseEntity.ok(
                "Successfully restarted preprocessing pipeline for batch " + uuid);
        } catch (IOException e) {
            log.error(
                "Failed to execute start-over pipeline re-queue mapping due to file system fault.",
                e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to start over due to an internal directory read failure: "
                    + e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Start over operation rejected by tracking coordinator layer.", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to restart. " + e.getMessage());
        }
    }

    /**
     * Fetches detailed task tracking statistics intended for frontend progress bar rendering. *
     *
     * @param uuid unique transaction tracking token
     * @return a TaskProgress payload mapping total, remaining, completed, and progress percentage
     */
    @GetMapping("/{uuid}/progress")
    public ResponseEntity<TaskProgress> getProgress(@PathVariable String uuid) {
        log.debug("Fetching preprocessing progress state metrics for batch UUID: [{}]", uuid);
        TaskProgress progress = taskCoordinatorService.getTaskProgress(uuid);
        return ResponseEntity.ok(progress);
    }

    /**
     * Evaluates whether the batch transaction has fully wrapped up background processing jobs. Used
     * primarily by frontends to toggle the disabled/enabled state of downstream UI workflow
     * components. * @param uuid unique transaction tracking token
     *
     * @return true if preprocessing is complete (meaning you can ENABLE the button); false if still
     * processing (meaning you should GREY OUT the button)
     */
    @GetMapping("/{uuid}/finished")
    public ResponseEntity<Boolean> isFinished(@PathVariable String uuid) {
        log.debug(
            "Polling status readiness for downstream UI component enablement on batch UUID: [{}]",
            uuid);
        boolean finished = taskCoordinatorService.isPreprocessFinished(uuid);
        return ResponseEntity.ok(finished);
    }
}