package com.wechat.wechatsummary.util;

import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared file path helpers for the media storage layout.
 */
@Slf4j
public final class PathUtils {

    private PathUtils() {
    }

    /**
     * Returns the portion of an absolute media file path that follows the {@code /{uuid}/} segment,
     * or the original path unchanged when no such segment exists.
     */
    public static String relativizeToUuid(String rawPath, String uuid) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        String targetSegment = "/" + uuid + "/";
        int index = rawPath.indexOf(targetSegment);
        if (index == -1) {
            return rawPath;
        }
        return rawPath.substring(index + targetSegment.length());
    }

    /**
     * Parses the leading numeric timestamp embedded in media file names like
     * {@code 1234567890_img.jpg}. Returns {@code 0} when the path is null or cannot be parsed.
     */
    public static long extractTimestamp(String filePath) {
        if (filePath == null) {
            return 0L;
        }
        try {
            String fileName = Paths.get(filePath).getFileName().toString();
            return Long.parseLong(fileName.split("_")[0]);
        } catch (Exception e) {
            log.warn("Failed to parse media timestamp from path: {}", filePath);
            return 0L;
        }
    }
}
