package com.wechat.wechatsummary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Guards the content sniffing that stops a corrupt media file from being shipped to the vision API.
 *
 * <p>The regression: an emoji whose decryption produced garbage (leading bytes {@code 61 1F 4F FC})
 * was sent to Gemini as {@code image/png} because the processor trusted the file name. The API
 * answered a permanent 400, which the retry ladder could not fix, so the batch sat at 281/282 for
 * ~4.5 minutes and the UI looked frozen.
 */
class MediaContentSnifferTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void detectsJpeg() {
        assertEquals("image/jpeg", MediaContentSniffer.detectImageMimeType(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
    }

    @Test
    void detectsPng() {
        assertEquals("image/png", MediaContentSniffer.detectImageMimeType(
            bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)));
    }

    @Test
    void detectsGif() {
        assertEquals("image/gif", MediaContentSniffer.detectImageMimeType(
            bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)));
    }

    @Test
    void detectsBmp() {
        assertEquals("image/bmp", MediaContentSniffer.detectImageMimeType(bytes(0x42, 0x4D, 0x36, 0x00)));
    }

    @Test
    void detectsWebpViaRiffMarker() {
        assertEquals("image/webp", MediaContentSniffer.detectImageMimeType(bytes(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)));
    }

    @Test
    void riffWithoutWebpMarker_isNotAnImage() {
        assertNull(MediaContentSniffer.detectImageMimeType(
            bytes(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45)));
    }

    /** The exact bytes of the file that stalled the real batch. */
    @Test
    void garbageFromFailedDecryption_isRejected() {
        byte[] garbage = bytes(0x61, 0x1F, 0x4F, 0xFC, 0x41, 0xAB, 0xF1, 0x6F, 0xD3, 0x5D, 0xAD, 0x4A);
        assertNull(MediaContentSniffer.detectImageMimeType(garbage));
        assertEquals("611F4FFC41ABF16F", MediaContentSniffer.describeHeader(garbage));
    }

    @Test
    void emptyAndNullInputs_areRejected() {
        assertNull(MediaContentSniffer.detectImageMimeType(null));
        assertNull(MediaContentSniffer.detectImageMimeType(new byte[0]));
        assertNull(MediaContentSniffer.detectImageMimeType(bytes(0x01, 0x02)));
    }

    @Test
    void describeHeader_handlesNullAndShortInput() {
        assertEquals("<empty>", MediaContentSniffer.describeHeader(null));
        assertEquals("<empty>", MediaContentSniffer.describeHeader(new byte[0]));
        assertEquals("0102", MediaContentSniffer.describeHeader(bytes(0x01, 0x02)));
    }
}
