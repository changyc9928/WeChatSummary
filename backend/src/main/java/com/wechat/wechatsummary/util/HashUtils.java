package com.wechat.wechatsummary.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared cryptographic helpers used across media processing pipelines.
 */
@Slf4j
public final class HashUtils {

    private HashUtils() {
    }

    /**
     * Computes the lowercase hex SHA-256 digest of the given input.
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("SHA-256 hashing failed.", e);
            throw new RuntimeException(e);
        }
    }
}
