package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import com.wechat.wechatsummary.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MediaProducerService {

    private final RabbitTemplate rabbitTemplate;
    private final TaskTaskCoordinatorService coordinatorService;
    private final StorageConfig storageConfig;

    public MediaProducerService(RabbitTemplate rabbitTemplate,
        TaskTaskCoordinatorService coordinatorService,
        StorageConfig storageConfig) {
        this.rabbitTemplate = rabbitTemplate;
        this.coordinatorService = coordinatorService;
        this.storageConfig = storageConfig;
    }

    public void preprocess(String uuid) throws IOException {
        Path baseDir = storageConfig.getUploadDir().resolve(uuid);

        // 1. 自动寻找该 UUID 目录下的原始群聊 JSON 文件 (比如：Vtuber&ACG X帝國.json)
        String inputJsonPath;
        try (Stream<Path> list = Files.list(baseDir)) {
            Optional<Path> jsonFile = list.filter(p -> p.toString().endsWith(".json")).findFirst();
            if (jsonFile.isEmpty()) {
                throw new RuntimeException("在目录 " + baseDir + " 下未找到任何聊天记录 JSON 原始文件！");
            }
            inputJsonPath = jsonFile.get().toAbsolutePath().toString();
        }

        // 2. 动态生成处理后的输出 JSON 文件路径
        String outputFilePath = storageConfig.getUploadDir()
            .resolve("outputs")
            .resolve(uuid + "_processed.md") // 输出也拉齐为 .md
            .toAbsolutePath()
            .toString();

        log.info("Found input JSON: {}. Target output JSON: {}", inputJsonPath, outputFilePath);

        Path imagesDir = baseDir.resolve("images");
        Path emojisDir = baseDir.resolve("emojis");
        Path voicesDir = baseDir.resolve("voices");

        int totalMediaFiles = countFiles(imagesDir) + countFiles(emojisDir) + countFiles(voicesDir);
        log.info("Total media files found for UUID {}: {}", uuid, totalMediaFiles);

        // 3. 将动态探测出来的 inputJsonPath 和 outputFilePath 一起送入上下文
        coordinatorService.initTaskContext(uuid, totalMediaFiles, inputJsonPath, outputFilePath);

        if (totalMediaFiles > 0) {
            scanAndPublish(uuid, imagesDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(uuid, emojisDir, RabbitConfig.IMAGE_ROUTING_KEY);
            scanAndPublish(uuid, voicesDir, RabbitConfig.AUDIO_ROUTING_KEY);
        }
    }

    private int countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> paths = Files.walk(dir)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }

    private void scanAndPublish(String uuid, Path dir, String routingKey) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                .forEach(file -> {
                    String messagePayload = uuid + ":" + file.toAbsolutePath().toString();
                    rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, messagePayload);
                });
        }
    }
}