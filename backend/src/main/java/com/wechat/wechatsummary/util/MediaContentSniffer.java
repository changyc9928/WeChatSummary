package com.wechat.wechatsummary.util;

/**
 * Content-based media type detection.
 *
 * <p>File extensions and {@link java.nio.file.Files#probeContentType} both trust the name rather
 * than the bytes, so a WeChat export whose decryption produced garbage (or any truncated/renamed
 * asset) is happily reported as {@code image/png}. Handing such bytes to a vision model produces a
 * deterministic 400 ("cannot identify image file"), and because that is indistinguishable from a
 * rate-limit the media pipeline used to retry it {@code MAX_RETRIES} times — roughly 4.5 minutes
 * per bad file — holding the whole batch counter open and making the run look frozen.
 *
 * <p>Sniffing the leading bytes lets the pipeline reject undecodable media locally and immediately,
 * so a single corrupt asset can no longer stall a session.
 */
public final class MediaContentSniffer {

    private MediaContentSniffer() {
    }

    /**
     * Returns the MIME type implied by the leading bytes, or {@code null} when the payload does not
     * match any supported image container. Never guesses from the file name.
     *
     * @param bytes raw file content (may be {@code null} or empty)
     * @return the detected MIME type, or {@code null} if unrecognized
     */
    public static String detectImageMimeType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(bytes, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        if (startsWith(bytes, 0x42, 0x4D)) {
            return "image/bmp";
        }
        // RIFF....WEBP — the four size bytes sit between the two markers.
        if (startsWith(bytes, 0x52, 0x49, 0x46, 0x46) && bytes.length >= 12
            && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    /**
     * Renders the leading bytes as uppercase hex for log messages, so an operator can see what the
     * file actually contained without opening it.
     *
     * @param bytes raw file content (may be {@code null})
     * @return up to the first 8 bytes as hex, or {@code "<empty>"}
     */
    public static String describeHeader(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        int len = Math.min(8, bytes.length);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
