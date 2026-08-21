package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Serves the Windows {@code bridge.exe} sidecar (wechat-key-bridge) as a
 * distributable download. The binary is deliberately NOT committed into the
 * repository; the operator builds it once
 * ({@code GOOS=windows GOARCH=amd64 go build -o bridge.exe ./cmd/bridge})
 * and drops it into {@code storage.tools-dir} (default {@code ./tools}).
 *
 * <p>The SHA-256 digest is computed lazily and cached against the file's
 * mtime+size so repeated meta requests do not re-hash a 7 MB binary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BridgeToolService {

    public static final String FILE_NAME = "bridge.exe";
    public static final String DOWNLOAD_PATH = "/api/tools/bridge/download";

    private final StorageConfig storageConfig;

    /**
     * Metadata for the frontend download button.
     */
    public record BridgeToolMeta(
        boolean present,
        String fileName,
        Long sizeBytes,
        String sha256,
        String downloadUrl,
        String help
    ) {
    }

    private record DigestCache(long lastModified, long size, String sha256) {
    }

    private volatile DigestCache digestCache;

    public Path file() {
        return storageConfig.getToolsDir().resolve(FILE_NAME);
    }

    public BridgeToolMeta meta() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return new BridgeToolMeta(false, FILE_NAME, null, null, DOWNLOAD_PATH, helpText(f));
        }
        try {
            long size = Files.size(f);
            return new BridgeToolMeta(true, FILE_NAME, size, sha256(f), DOWNLOAD_PATH, null);
        } catch (IOException e) {
            log.warn("Cannot stat bridge tool {}: {}", f, e.toString());
            return new BridgeToolMeta(false, FILE_NAME, null, null, DOWNLOAD_PATH, helpText(f));
        }
    }

    /**
     * Streams the binary as an attachment, or returns a 404 envelope telling
     * the operator where to put the file.
     */
    public ResponseEntity<?> download() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(com.wechat.wechatsummary.dto.ApiResponse.error(404, helpText(f)));
        }
        Resource resource = new FileSystemResource(f);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", FILE_NAME);
        // Never let a browser reuse an earlier download of this URL: the
        // operator ships new bridge builds through the same endpoint, and a
        // cached body (older SHA) is indistinguishable from a fresh one.
        headers.setCacheControl("no-store, no-cache, must-revalidate, max-age=0");
        headers.setPragma("no-cache");
        try {
            headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(resource.contentLength()));
            headers.set("X-Bridge-SHA256", sha256(f));
        } catch (IOException e) {
            log.warn("Cannot read bridge tool metadata: {}", e.toString());
        }
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    String helpText(Path f) {
        return "bridge.exe is not present in " + f.toAbsolutePath().normalize()
            + ". Build it with: cd exporter && GOOS=windows GOARCH=amd64 go build -o bridge.exe ./cmd/bridge"
            + " (or mount the file via the tools volume), then refresh.";
    }

    private String sha256(Path f) throws IOException {
        long mtime = Files.getLastModifiedTime(f).toMillis();
        long size = Files.size(f);
        DigestCache cache = digestCache;
        if (cache != null && cache.lastModified == mtime && cache.size == size) {
            return cache.sha256;
        }
        byte[] digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            digest = md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        String hex = HexFormat.of().formatHex(digest);
        digestCache = new DigestCache(mtime, size, hex);
        return hex;
    }
}