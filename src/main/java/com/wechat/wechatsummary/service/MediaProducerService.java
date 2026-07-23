package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Service responsible for scanning uploaded batch assets, initializing task coordination states,
 * and producing messaging payloads to RabbitMQ for individual asynchronous media (images, emojis,
 * voice notes) processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final TaskTaskCoordinatorService coordinatorService;
    private final StorageConfig storageConfig;

    /**
     * Preprocesses an uploaded session directory by scanning for a source chat log JSON file,
     * resolving structured output file paths, initializing centralized tracking contexts, and
     * dispatching media file paths to RabbitMQ queues.
     *
     * @param uuid unique identifier representing the active upload transaction session
     * @throws IOException      if an I/O error occurs during file or directory traversals
     * @throws RuntimeException if no valid raw chat log JSON data can be discovered inside the
     *                          target workspace
     */
    public void preprocess(String uuid) throws IOException {
        // 0. Prevent duplicate execution if the task is already running
        TaskProgress progress = coordinatorService.getTaskProgress(uuid);
        if ("RUNNING".equalsIgnoreCase(progress.getStatus())) {
            log.warn("Preprocessing skipped for UUID: [{}]. Task is already RUNNING.", uuid);
            return;
        }

        log.info("Starting media file preprocessing lifecycle phase for transaction UUID: [{}]",
            uuid);
        Path baseDir = storageConfig.getUploadDir().resolve(uuid);

        // 1. Automatically scan for the source chat history log JSON file within the active UUID workspace directory
        String inputJsonPath;
        try (Stream<Path> list = Files.list(baseDir)) {
            Optional<Path> jsonFile = list.filter(p -> p.toString().endsWith(".json")).findFirst();
            if (jsonFile.isEmpty()) {
                log.error(
                    "Preprocessing terminal fault. Failed to locate raw chat JSON log structure inside base directory: {}",
                    baseDir);
                throw new RuntimeException(
                    "在目录 " + baseDir + " 下未找到任何聊天记录 JSON 原始文件！");
            }
            inputJsonPath = jsonFile.get().toAbsolutePath().toString();
        }

        // 2. Dynamically resolve the absolute file path for the target compiled output document (.md format)
        String outputFilePath = storageConfig.getUploadDir()
            .resolve("outputs")
            .resolve(uuid + "_processed.md")
            .toAbsolutePath()
            .toString();

        log.info(
            "Asset tracking finalized. Input JSON resolved to: [{}]. Configured final output location: [{}]",
            inputJsonPath, outputFilePath);

        Path imagesDir = baseDir.resolve("images");
        Path emojisDir = baseDir.resolve("emojis");
        Path voicesDir = baseDir.resolve("voices");

        int totalMediaFiles = countFiles(imagesDir) + countFiles(emojisDir) + countFiles(voicesDir);
        log.info(
            "Media file discovery scan completed for UUID: [{}]. Aggregate media items discovered: {}",
            uuid, totalMediaFiles);

        // 3. Register the discovered target file mappings and count totals into the active Task Coordinator Context
        coordinatorService.initTaskContext(uuid, totalMediaFiles, inputJsonPath, outputFilePath);

        if (totalMediaFiles > 0) {
            log.info(
                "Publishing media parsing items to message broker exchange for parallel ingestion routing...");
            scanAndPublish(uuid, imagesDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(uuid, emojisDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(uuid, voicesDir, RabbitConfig.AUDIO_ROUTING_KEY);

            if (!coordinatorService.isAborted(uuid)) {
                log.info(
                    "Successfully dispatched all discovery tasks for batch UUID: [{}] to AMQP Broker.",
                    uuid);
            }
        } else {
            log.warn(
                "Zero media attachments detected for pipeline UUID: [{}]. Skipping messaging ingestion stage.",
                uuid);
        }
    }

    /**
     * Counts the number of regular files contained recursively inside a given directory.
     *
     * @param dir path targeting the file directory to walk through
     * @return count representing total normal files within the path scope
     * @throws IOException if a file system error prevents reading directory structure metrics
     */
    private int countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            if (log.isDebugEnabled()) {
                log.debug(
                    "Directory target skipped during counting phase. Target path does not exist on disk: {}",
                    dir);
            }
            return 0;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Walks a given directory path recursively, filtering out regular files, and dispatches a
     * formatted pipeline string token payload to the configured AMQP message exchange. Checks if
     * the abort flag is present in Redis prior to publishing each item.
     *
     * @param uuid       the tracing token associated with the root transaction task
     * @param dir        the target data directory path to scan through
     * @param routingKey standard AMQP binding criteria defining target processing queue endpoints
     * @throws IOException if file streaming or scanning steps encounter an unexpected IO system
     *                     break
     */
    private void scanAndPublish(String uuid, Path dir, String routingKey) throws IOException {
        if (!Files.exists(dir)) {
            log.warn(
                "Aborting asset message routing phase for directory mapping. Path does not exist: {}",
                dir);
            return;
        }

        log.info("Scanning directory: [{}] for message routing key emission: [{}]",
            dir.getFileName(), routingKey);

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                // Check if the batch task was aborted prior to pushing each message
                if (coordinatorService.isAborted(uuid)) {
                    log.warn(
                        "Task UUID [{}] has been ABORTED. Ceasing further message production for directory: {}",
                        uuid, dir);
                    break;
                }

                String messagePayload = uuid + ":" + file.toAbsolutePath().toString();

                if (log.isDebugEnabled()) {
                    log.debug(
                        "Dispatching AMQP frame payload mapping: [{}] onto routing address: [{}]",
                        messagePayload, routingKey);
                }

                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, messagePayload);
            }
        }
    }
}