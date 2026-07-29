package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.dto.TaskProgress;
import com.wechat.wechatsummary.dto.TaskStatus;
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
 * Service responsible for scanning uploaded batch assets under user-isolated directories,
 * initializing task coordination states, and producing messaging payloads to RabbitMQ for individual
 * asynchronous media (images, emojis, voice notes) processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final TaskTaskCoordinatorService coordinatorService;
    private final StorageConfig storageConfig;

    /**
     * Preprocesses an uploaded session directory scoped under the specified user UUID by scanning for a
     * source chat log JSON file, resolving structured output file paths, initializing centralized tracking
     * contexts, and dispatching media file paths to RabbitMQ queues.
     *
     * @param userId unique identifier representing the user UUID
     * @param uuid   unique identifier representing the active upload transaction session
     * @throws IOException      if an I/O error occurs during file or directory traversals
     * @throws RuntimeException if no valid raw chat log JSON data can be discovered inside the
     *                          target workspace
     */
    public void preprocess(String userId, String uuid) throws IOException {
        // 0. Prevent duplicate execution if the task is already running
        TaskProgress progress = coordinatorService.getTaskProgress(uuid);
        if (TaskStatus.RUNNING.equals(progress.getStatus())) {
            log.warn("Preprocessing skipped for user UUID: [{}] and session UUID: [{}]. Task is already RUNNING.", userId, uuid);
            return;
        }

        log.info("Starting media file preprocessing lifecycle phase for user UUID: [{}] and transaction session UUID: [{}]",
            userId, uuid);

        // Resolve path inside the user's isolated directory
        Path baseDir = storageConfig.getUploadDir().resolve(userId).resolve(uuid);

        // 1. Automatically scan for the source chat history log JSON file within the active session workspace directory
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

        // 2. Dynamically resolve the absolute file path for the target compiled output document (.md format) scoped by user
        Path userOutputDir = storageConfig.getUploadDir().resolve(userId).resolve("outputs");
        Files.createDirectories(userOutputDir);
        String outputFilePath = userOutputDir
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
            "Media file discovery scan completed for user UUID: [{}] and session UUID: [{}]. Aggregate media items discovered: {}",
            userId, uuid, totalMediaFiles);

        // 3. Register userId, session uuid, target file mappings, and counts into the active Task Coordinator Context
        coordinatorService.initTaskContext(userId, uuid, totalMediaFiles, inputJsonPath, outputFilePath);

        if (totalMediaFiles > 0) {
            log.info(
                "Publishing media parsing items to message broker exchange for parallel ingestion routing...");
            scanAndPublish(userId, uuid, imagesDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(userId, uuid, emojisDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(userId, uuid, voicesDir, RabbitConfig.AUDIO_ROUTING_KEY);

            if (!coordinatorService.isAborted(uuid)) {
                log.info(
                    "Successfully dispatched all discovery tasks for user UUID: [{}] and batch UUID: [{}] to AMQP Broker.",
                    userId, uuid);
            }
        } else {
            log.warn(
                "Zero media attachments detected for user UUID: [{}] and pipeline session UUID: [{}]. Skipping messaging ingestion stage.",
                userId, uuid);
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
     * @param userId     unique user identifier isolating the file directories
     * @param uuid       the tracing token associated with the root transaction task
     * @param dir        the target data directory path to scan through
     * @param routingKey standard AMQP binding criteria defining target processing queue endpoints
     * @throws IOException if file streaming or scanning steps encounter an unexpected IO system
     *                     break
     */
    private void scanAndPublish(String userId, String uuid, Path dir, String routingKey) throws IOException {
        if (!Files.exists(dir)) {
            log.warn("Aborting asset message routing phase for directory mapping. Path does not exist: {}", dir);
            return;
        }

        log.info("Scanning directory: [{}] for message routing key emission: [{}]", dir.getFileName(), routingKey);

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                if (coordinatorService.isAborted(uuid)) {
                    log.warn("Task UUID [{}] has been ABORTED. Ceasing further message production for directory: {}", uuid, dir);
                    break;
                }

                // Include userId in the message payload string: "userId:uuid:absoluteFilePath"
                String messagePayload = userId + ":" + uuid + ":" + file.toAbsolutePath().toString();

                if (log.isDebugEnabled()) {
                    log.debug("Dispatching AMQP frame payload mapping: [{}] onto routing address: [{}]", messagePayload, routingKey);
                }

                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, messagePayload);
            }
        }
    }
}