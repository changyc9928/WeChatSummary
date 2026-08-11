package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.VideoSummary;
import com.wechat.wechatsummary.util.HashUtils;
import com.wechat.wechatsummary.util.PageUtils;
import com.wechat.wechatsummary.util.PathUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Service orchestration class managing video summary processing lifecycle.
 * Treats cacheService as the black-box abstraction for video record persistence and retrieval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoProcessorService {

    private static final String VIDEO_MIME_TYPE = "video/mp4";

    @org.springframework.beans.factory.annotation.Value("${custom-ai.video.frame-count:5}")
    private int frameCount;

    private final AiService aiService;
    private final WeChatSummaryCacheService cacheService;
    private final MediaFileResourceLoader fileResourceLoader;
    private final StoragePaths storagePaths;

    public void processVideoSummary(String filePath) {
        log.info("Initiating video processing pipeline execution for target file: [{}]", filePath);
        try {
            String hash = HashUtils.sha256(filePath);

            Optional<VideoSummary> dbRecord = cacheService.findVideoSummaryByHash(hash);

            if (dbRecord.isPresent() && dbRecord.get().getSummary() != null && !dbRecord.get().getSummary().isBlank()) {
                log.info("Trace match hit for video hash: [{}]. Skipping duplicate AI processing.", hash);
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("Video processing aborted. Resource file does not exist at path: {}", filePath);
                return;
            }

            VideoSummary entity;
            if (dbRecord.isPresent()) {
                entity = dbRecord.get();
            } else {
                entity = new VideoSummary();
                entity.setId(hash);
                entity.setFileHash(hash);
                entity.setFilePath(filePath);
                entity.setCreatedAt(LocalDateTime.now());
            }

            // Extract frames, transcribe frames using AI, and combine into video summary
            List<byte[]> frames = extractFrames(filePath, frameCount);
            if (!frames.isEmpty()) {
                List<String> transcriptions = new java.util.ArrayList<>();
                for (int i = 0; i < frames.size(); i++) {
                    try {
                        String frameTranscription = aiService.transcribeVideoFrame(frames.get(i), i + 1);
                        transcriptions.add(frameTranscription);
                    } catch (Exception ex) {
                        log.warn("Failed to transcribe frame #{} for video {}: {}", i + 1, filePath, ex.getMessage());
                    }
                }

                if (!transcriptions.isEmpty()) {
                    String unifiedTranscript = String.join("\n\n--- Frame Breakdown ---\n\n", transcriptions);
                    entity.setTranscript(unifiedTranscript);

                    String finalSummary = aiService.summarizeVideoTranscriptions(transcriptions);
                    entity.setSummary(finalSummary);
                }
            } else {
                log.warn("No frames extracted for video file: {}", filePath);
            }

            cacheService.saveVideoSummary(entity);
            log.info("Video processing pipeline executed successfully for hash: [{}]", hash);

        } catch (Exception e) {
            log.error("Fatal exception encountered while processing video resource context: {}", filePath, e);
        }
    }

    public Page<VideoSummary> getVideoSummariesByUuid(String uuid, Pageable pageable) {
        List<VideoSummary> entities = cacheService.getVideoSummariesByUuid(uuid);

        List<VideoSummary> processedList = entities.stream()
            .map(entity -> sanitizeFilePath(entity, uuid))
            .sorted(Comparator.comparingLong(
                entity -> PathUtils.extractTimestamp(entity.getFilePath())))
            .toList();

        return PageUtils.paginate(processedList, pageable);
    }

    private VideoSummary sanitizeFilePath(VideoSummary original, String uuid) {
        VideoSummary sanitized = new VideoSummary();
        sanitized.setId(original.getId());
        sanitized.setFileHash(original.getFileHash());
        sanitized.setTranscript(original.getTranscript());
        sanitized.setSummary(original.getSummary());
        sanitized.setCreatedAt(original.getCreatedAt());
        sanitized.setFilePath(PathUtils.relativizeToUuid(original.getFilePath(), uuid));
        return sanitized;
    }

    public void deleteVideoSummaryById(String id) {
        cacheService.findVideoSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.deleteVideoSummaryById(id);
    }

    public void deleteVideoSummariesByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findVideoSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.deleteVideoSummariesByIds(ids);
    }

    private void invalidateProcessedMarkdown(VideoSummary entity) {
        if (entity.getFilePath() == null || entity.getFilePath().isBlank()) {
            return;
        }
        storagePaths.deleteProcessedMarkdownFor(Paths.get(entity.getFilePath()));
    }

    public Optional<MediaFileResourceLoader.MediaFileResource> getVideoFileById(String id) {
        return cacheService.findVideoSummaryByHash(id)
            .map(VideoSummary::getFilePath)
            .flatMap(path -> fileResourceLoader.load(path, VIDEO_MIME_TYPE));
    }

    public void clearVideoSummaryTextById(String id) {
        cacheService.findVideoSummaryByHash(id).ifPresent(this::invalidateProcessedMarkdown);
        cacheService.clearVideoSummaryTextById(id);
    }

    public void clearVideoSummaryTextsByIds(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                cacheService.findVideoSummaryByHash(id)
                    .ifPresent(this::invalidateProcessedMarkdown);
            }
        }
        cacheService.clearVideoSummaryTextsByIds(ids);
    }

    public void deleteAllVideoSummariesByUuid(String uuid) {
        log.info("Deleting all video summaries for session UUID: [{}]", uuid);
        List<VideoSummary> entities = cacheService.getVideoSummariesByUuid(uuid);
        if (entities != null && !entities.isEmpty()) {
            invalidateProcessedMarkdown(entities.get(0));

            List<String> ids = entities.stream().map(VideoSummary::getId).toList();
            cacheService.deleteVideoSummariesByIds(ids);
        }
    }
    List<byte[]> extractFrames(String videoPathStr, int count) {
        List<byte[]> frameBytesList = new java.util.ArrayList<>();
        try {
            Path videoPath = Paths.get(videoPathStr);
            Path tempDir = Files.createTempDirectory("video_frames_");
            try {
                // Call ffmpeg to extract frames across duration using select filter or rate
                String outputPattern = tempDir.resolve("frame_%03d.jpg").toString();
                // Extract roughly 'count' frames spread across duration
                // First get video duration if possible or extract frames at even intervals
                ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", videoPath.toString(),
                    "-vf", "fps=1",
                    "-vframes", String.valueOf(count),
                    outputPattern
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    try (java.util.stream.Stream<Path> stream = Files.list(tempDir)) {
                        List<Path> extractedFiles = stream.sorted().toList();
                        for (Path frameFile : extractedFiles) {
                            frameBytesList.add(Files.readAllBytes(frameFile));
                        }
                    }
                } else {
                    log.error("ffmpeg frame extraction failed with exit code: {}", exitCode);
                }
            } finally {
                // Cleanup temp dir
                try (java.util.stream.Stream<Path> stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract frames from video: {}", videoPathStr, e);
        }
        return frameBytesList;
    }
}
