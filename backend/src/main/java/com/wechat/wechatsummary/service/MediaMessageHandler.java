package com.wechat.wechatsummary.service;

import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Handles the shared AMQP consumer lifecycle for media queue messages: payload parsing, abort
 * checks, thread registration, processing and completion reporting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaMessageHandler {

    private final TaskCoordinatorService coordinatorService;

    /**
     * Parses a {@code userId:uuid:filePath} message and runs the given processor against the
     * contained file path.
     *
     * @param message    raw queue payload
     * @param mediaType  human-readable media kind used in logs (e.g. "audio", "image")
     * @param processor  downstream processing step keyed by file path
     */
    public void handle(String message, String mediaType, Consumer<String> processor) {
        log.info("Received {} message: {}", mediaType, message);
        String[] parts = message.split(":", 3);
        if (parts.length < 3) {
            log.error("Malformed {} queue message payload received: {}", mediaType, message);
            return;
        }

        String uuid = parts[1];
        String filePath = parts[2];

        if (coordinatorService.isAborted(uuid)) {
            log.warn("Task UUID [{}] has been explicitly ABORTED. DROPPING message safely.", uuid);
            return;
        }

        coordinatorService.registerThread(uuid, Thread.currentThread());

        try {
            processor.accept(filePath);
        } catch (Exception e) {
            log.error("Failed to process {} file, path: {}. Skipping.", mediaType, filePath, e);
        } finally {
            coordinatorService.completeTask(uuid, Thread.currentThread());
        }
    }
}