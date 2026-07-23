package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.service.MediaProducerService;
import com.wechat.wechatsummary.service.TaskTaskCoordinatorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to manually kick off batch file ingestion,
 * system validation scanning, aborting tasks, and querying execution progress.
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
}