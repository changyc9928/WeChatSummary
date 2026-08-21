package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wechat.wechatsummary.config.StorageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeToolServiceTest {

    @TempDir
    Path tmp;

    private BridgeToolService service() {
        StorageConfig cfg = new StorageConfig();
        cfg.setToolsDir(tmp);
        return new BridgeToolService(cfg);
    }

    @Test
    void metaReportsAbsentWithHelp() {
        BridgeToolService.BridgeToolMeta meta = service().meta();
        assertFalse(meta.present());
        assertNull(meta.sha256());
        assertTrue(meta.help().contains("bridge.exe"), "help should mention the file");
    }

    @Test
    void metaReportsPresentWithSha256() throws Exception {
        byte[] payload = "hello bridge".getBytes();
        Files.write(tmp.resolve("bridge.exe"), payload);

        BridgeToolService.BridgeToolMeta meta = service().meta();
        assertTrue(meta.present());
        assertEquals((long) payload.length, meta.sizeBytes());
        String expected = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(payload));
        assertEquals(expected, meta.sha256());
    }

    @Test
    void download404WhenAbsent() {
        assertEquals(404, service().download().getStatusCode().value());
    }

    @Test
    void downloadServesFileWithChecksumHeader() throws Exception {
        Files.write(tmp.resolve("bridge.exe"), "exe-bytes".getBytes());
        var resp = service().download();
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getHeaders().getFirst("X-Bridge-SHA256"));
        assertEquals("bridge.exe", resp.getHeaders().getContentDisposition().getFilename());
    }
}
