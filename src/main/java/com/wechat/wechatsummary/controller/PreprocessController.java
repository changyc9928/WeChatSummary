package com.wechat.wechatsummary.controller;

import com.wechat.wechatsummary.service.MediaProducerService;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Constructs a new PreprocessController with the required streaming orchestrator service.
     */
    public PreprocessController(MediaProducerService producerService) {
        this.producerService = producerService;
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
}