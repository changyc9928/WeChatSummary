package com.wechat.wechatsummary.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionIdExtractorTest {

    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void extractsUuidAboveMediaDir() {
        assertEquals(Optional.of(UUID), SessionIdExtractor.extractSessionId(
                "/app/uploads/user-1/" + UUID + "/images/123_img.jpg"));
    }

    @Test
    void extractsForAllMediaDirs() {
        for (String dir : new String[]{"images", "emojis", "voices", "videos"}) {
            assertEquals(Optional.of(UUID), SessionIdExtractor.extractSessionId(
                    "/data/u/" + UUID + "/" + dir + "/f.bin"), "dir: " + dir);
        }
    }

    @Test
    void extractsUuidWithoutKnownMediaDir() {
        assertEquals(Optional.of(UUID), SessionIdExtractor.extractSessionId(
                "/app/uploads/user-1/" + UUID + "/weird/123.bin"));
    }

    @Test
    void rejectsNonUuidSegment() {
        assertEquals(Optional.empty(), SessionIdExtractor.extractSessionId(
                "/app/uploads/user-1/not-a-uuid/images/123.jpg"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals(Optional.empty(), SessionIdExtractor.extractSessionId(null));
        assertEquals(Optional.empty(), SessionIdExtractor.extractSessionId("  "));
    }

    @Test
    void handlesWindowsSeparators() {
        assertEquals(Optional.of(UUID), SessionIdExtractor.extractSessionId(
                "C:\\uploads\\user-1\\" + UUID + "\\images\\1.jpg"));
    }

    @Test
    void ignoresUuidInFileName() {
        // A uuid appearing only in the file name (no parent segment) must not match.
        assertTrue(SessionIdExtractor.extractSessionId(UUID + ".jpg").isEmpty());
    }
}
