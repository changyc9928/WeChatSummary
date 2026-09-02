package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZipExtractionServiceTest {

    private static final long TS_START = 1788159635L;
    private static final long TS_END = 1788321600L;

    @TempDir
    Path tmp;

    private ZipExtractionService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private StoragePaths storagePaths;

    @BeforeEach
    void setUp() {
        service = new ZipExtractionService(storagePaths, objectMapper);
    }

    private String callBuildChatDisplayTitle(Path directory) throws Exception {
        Method method = ZipExtractionService.class.getDeclaredMethod(
            "buildChatDisplayTitle", Path.class);
        method.setAccessible(true);
        return (String) method.invoke(service, directory);
    }

    private void writeJson(Path dir, String filename, String json) throws Exception {
        Files.writeString(dir.resolve(filename), json);
    }

    @Test
    void testNewFormat() throws Exception {
        Path dir = tmp.resolve("session1");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"nickname\":\"Test Chat\",\"messageCount\":3},"
            + "\"messages\":["
            + "{\"createTime\":" + TS_START + "},"
            + "{\"createTime\":" + TS_START + "},"
            + "{\"createTime\":" + TS_END + "}"
            + "]}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Test Chat"));
        assertTrue(title.contains("2026-08-31"));
        assertTrue(title.contains("2026-09-02"));
    }

    @Test
    void testNewFormatMessagesOutOfOrder() throws Exception {
        Path dir = tmp.resolve("session2");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"nickname\":\"Test Chat\"},"
            + "\"messages\":["
            + "{\"createTime\":" + TS_END + "},"
            + "{\"createTime\":" + TS_START + "},"
            + "{\"createTime\":" + TS_START + "}"
            + "]}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Test Chat"));
        assertTrue(title.contains("2026-08-31"));
        assertTrue(title.contains("2026-09-02"));
    }

    @Test
    void testOldFormat() throws Exception {
        Path dir = tmp.resolve("session3");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"nickname\":\"Old Chat\","
            + "\"firstTimestamp\":" + TS_START + ","
            + "\"lastTimestamp\":" + TS_END + "},"
            + "\"messages\":[]}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Old Chat"));
        assertTrue(title.contains("2026-08-31"));
        assertTrue(title.contains("2026-09-02"));
    }

    @Test
    void testNewFormatWithoutNicknameUsesFilenameFallback() throws Exception {
        Path dir = tmp.resolve("MyChat");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"messageCount\":5},"
            + "\"messages\":[{\"createTime\":" + TS_START + "}]}";
        writeJson(dir, "MyChat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.startsWith("MyChat"));
        assertTrue(title.contains("2026-08-31"));
    }

    @Test
    void testNewFormatWithInvalidTimestamps() throws Exception {
        Path dir = tmp.resolve("session5");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"nickname\":\"Test Chat\"},"
            + "\"messages\":["
            + "{\"createTime\":0},"
            + "{\"createTime\":-1},"
            + "{},"
            + "{\"createTime\":" + TS_START + "}"
            + "]}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Test Chat"));
        assertTrue(title.contains("2026-08-31"));
    }

    @Test
    void testNoValidTimestamps() throws Exception {
        Path dir = tmp.resolve("session6");
        Files.createDirectories(dir);
        String json = "{\"session\":{\"nickname\":\"Test Chat\"},"
            + "\"messages\":["
            + "{\"createTime\":0},"
            + "{}"
            + "]}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertEquals("Test Chat", title);
    }

    @Test
    void testMalformedJson() throws Exception {
        Path dir = tmp.resolve("session7");
        Files.createDirectories(dir);
        writeJson(dir, "bad.json", "{invalid json content {{{");

        String title = callBuildChatDisplayTitle(dir);
        assertEquals("bad", title);
    }

    @Test
    void testLargeMessageArray() throws Exception {
        Path dir = tmp.resolve("session8");
        Files.createDirectories(dir);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"session\":{\"nickname\":\"Large Chat\"},\"messages\":[");
        for (int i = 0; i < 100000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"createTime\":").append(1788159635L + i).append("}");
        }
        sb.append("]}");
        writeJson(dir, "chat.json", sb.toString());

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Large Chat"));
        assertTrue(title.contains("2026-08-31"));
    }

    @Test
    void testEmptyDirectory() throws Exception {
        String title = callBuildChatDisplayTitle(tmp);
        assertTrue(title.endsWith("(Empty Context)"));
    }

    @Test
    void testOldFormatFieldsInAnyOrder() throws Exception {
        Path dir = tmp.resolve("session10");
        Files.createDirectories(dir);
        String json = "{\"messages\":[],"
            + "\"session\":{\"nickname\":\"Ordered Chat\","
            + "\"lastTimestamp\":" + TS_END + ","
            + "\"firstTimestamp\":" + TS_START + "}}";
        writeJson(dir, "chat.json", json);

        String title = callBuildChatDisplayTitle(dir);
        assertTrue(title.contains("Ordered Chat"));
        assertTrue(title.contains("2026-08-31"));
        assertTrue(title.contains("2026-09-02"));
    }
}