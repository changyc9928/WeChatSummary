package com.wechat.wechatsummary.cache;

import java.util.Optional;
import java.util.UUID;

/**
 * Extracts the owning session UUID from a stored media file path.
 *
 * <p>Storage layout is {@code uploadDir / {userId} / {uuid} / ...}, so the session id is the path
 * segment directly above the media-type directory. Only well-formed UUID segments are accepted;
 * anything else yields empty so callers fall back to a safe (broader) invalidation instead of
 * evicting the wrong session's entries.
 */
public final class SessionIdExtractor {

    private SessionIdExtractor() {
    }

    /**
     * @param filePath stored absolute media file path, may be null/blank
     * @return the session UUID string when it can be derived reliably, otherwise empty
     */
    public static Optional<String> extractSessionId(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        String normalized = filePath.replace('\\', '/');
        String[] segments = normalized.split("/");
        // Walk from the file name upwards: the media directory (images/emojis/voices/videos) sits
        // directly below the session UUID directory.
        for (int i = segments.length - 1; i >= 0; i--) {
            if (isMediaDir(segments[i]) && i > 0 && isUuid(segments[i - 1])) {
                return Optional.of(segments[i - 1]);
            }
        }
        // Fallback: any well-formed UUID segment that is not the trailing file name.
        for (int i = 0; i < segments.length - 1; i++) {
            if (isUuid(segments[i])) {
                return Optional.of(segments[i]);
            }
        }
        return Optional.empty();
    }

    private static boolean isMediaDir(String segment) {
        return "images".equalsIgnoreCase(segment)
                || "emojis".equalsIgnoreCase(segment)
                || "voices".equalsIgnoreCase(segment)
                || "videos".equalsIgnoreCase(segment);
    }

    private static boolean isUuid(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(segment.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
