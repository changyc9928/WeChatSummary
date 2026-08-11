package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.VideoSummary;
import com.wechat.wechatsummary.util.HashUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VideoProcessorServiceTest {

    @Autowired
    private VideoProcessorService videoProcessorService;

    @Autowired
    private WeChatSummaryCacheService cacheService;

    @Test
    void testExtractFrames() {
        List<byte[]> frames = videoProcessorService.extractFrames("../uploads/test_video.mp4", 5);
        assertNotNull(frames);
        assertFalse(frames.isEmpty());
        assertTrue(frames.size() <= 5);
    }

    @Test
    void testFullVideoProcessingPipeline() throws Exception {
        String testVideoPath = "uploads/test_run.mp4";
        File testDir = new File("uploads");
        if (!testDir.exists()) {
            testDir.mkdirs();
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi",
            "-i", "testsrc=duration=5:size=320x240:rate=1",
            "-c:v", "libx264", testVideoPath
        );
        assertEquals(0, pb.start().waitFor(), "FFmpeg test video creation failed");

        try {
            videoProcessorService.processVideoSummary(testVideoPath);

            String hash = HashUtils.sha256(testVideoPath);
            Optional<VideoSummary> summaryOpt = cacheService.findVideoSummaryByHash(hash);

            assertTrue(summaryOpt.isPresent(), "Video summary should be saved to database/cache");
            VideoSummary summary = summaryOpt.get();
            assertNotNull(summary.getSummary());
            assertFalse(summary.getSummary().isBlank());
        } finally {
            new File(testVideoPath).delete();
        }
    }
}
