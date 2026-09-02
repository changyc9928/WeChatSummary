package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    private void callExtractZipSafely(Path zipFile, Path targetDir) throws Exception {
        Method method = ZipExtractionService.class.getDeclaredMethod(
            "extractZipSafely", Path.class, Path.class);
        method.setAccessible(true);
        method.invoke(service, zipFile, targetDir);
    }

    private Path createTestZip(String zipName, Map<String, String> entries) throws IOException {
        Path zipFile = tmp.resolve(zipName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                zos.putNextEntry(ze);
                if (!entry.getKey().endsWith("/") && entry.getValue() != null) {
                    zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    private Path createTestZip(String zipName, String... entryNames) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String name : entryNames) {
            entries.put(name, name.endsWith("/") ? null : "content of " + name);
        }
        return createTestZip(zipName, entries);
    }

    private Path extractTo(String zipName, String... entryNames) throws Exception {
        Path zipFile = createTestZip(zipName, entryNames);
        Path extractDir = tmp.resolve("extract-" + zipName.replace(".zip", ""));
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);
        return extractDir;
    }

    // --- buildChatDisplayTitle tests (preserved from original) ---

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

    // --- Zip extraction tests ---

    @Test
    void testNormalArchiveNoFlattening() throws Exception {
        Path extractDir = extractTo("normal", "xxx/aaa.txt");
        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertFalse(Files.exists(extractDir.resolve("aaa.txt")));
        assertEquals("content of xxx/aaa.txt",
            Files.readString(extractDir.resolve("xxx/aaa.txt")));
    }

    @Test
    void testNormalNestedArchiveNoFlattening() throws Exception {
        Path extractDir = extractTo("nested", "xxx/sub/aaa.txt");
        assertTrue(Files.exists(extractDir.resolve("xxx/sub/aaa.txt")));
        assertFalse(Files.exists(extractDir.resolve("sub/aaa.txt")));
        assertEquals("content of xxx/sub/aaa.txt",
            Files.readString(extractDir.resolve("xxx/sub/aaa.txt")));
    }

    @Test
    void testRedundantWrapperFlattened() throws Exception {
        Path extractDir = extractTo("redundant", "xxx/xxx/aaa.txt");
        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertFalse(Files.exists(extractDir.resolve("xxx/xxx/aaa.txt")));
        assertEquals("content of xxx/xxx/aaa.txt",
            Files.readString(extractDir.resolve("xxx/aaa.txt")));
    }

    @Test
    void testRedundantWrapperWithNestingFlattened() throws Exception {
        Path extractDir = extractTo("redundant-nested", "xxx/xxx/sub/aaa.txt");
        assertTrue(Files.exists(extractDir.resolve("xxx/sub/aaa.txt")));
        assertFalse(Files.exists(extractDir.resolve("xxx/xxx/sub/aaa.txt")));
        assertEquals("content of xxx/xxx/sub/aaa.txt",
            Files.readString(extractDir.resolve("xxx/sub/aaa.txt")));
    }

    @Test
    void testRedundantWrapperWithSiblingDoesNotFlatten() throws Exception {
        Path extractDir = extractTo("no-flatten-sibling",
            "xxx/xxx/aaa.txt", "xxx/other.txt");
        assertTrue(Files.exists(extractDir.resolve("xxx/xxx/aaa.txt")));
        assertTrue(Files.exists(extractDir.resolve("xxx/other.txt")));
        assertFalse(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertEquals("content of xxx/xxx/aaa.txt",
            Files.readString(extractDir.resolve("xxx/xxx/aaa.txt")));
        assertEquals("content of xxx/other.txt",
            Files.readString(extractDir.resolve("xxx/other.txt")));
    }

    @Test
    void testMultipleTopLevelDirsDoesNotFlatten() throws Exception {
        Path extractDir = extractTo("multi-root", "foo/aaa.txt", "bar/bbb.txt");
        assertTrue(Files.exists(extractDir.resolve("foo/aaa.txt")));
        assertTrue(Files.exists(extractDir.resolve("bar/bbb.txt")));
        assertEquals("content of foo/aaa.txt",
            Files.readString(extractDir.resolve("foo/aaa.txt")));
        assertEquals("content of bar/bbb.txt",
            Files.readString(extractDir.resolve("bar/bbb.txt")));
    }

    @Test
    void testRootLevelFileUnchanged() throws Exception {
        Path extractDir = extractTo("root-file", "aaa.txt");
        assertTrue(Files.exists(extractDir.resolve("aaa.txt")));
        assertEquals("content of aaa.txt",
            Files.readString(extractDir.resolve("aaa.txt")));
    }

    @Test
    void testArchiveWithoutExplicitDirectoryEntries() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/aaa.txt", "hello");
        entries.put("xxx/sub/bbb.txt", "world");
        Path zipFile = createTestZip("no-dir-entries.zip", entries);
        Path extractDir = tmp.resolve("extract-no-dir");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertTrue(Files.exists(extractDir.resolve("xxx/sub/bbb.txt")));
        assertEquals("hello", Files.readString(extractDir.resolve("xxx/aaa.txt")));
        assertEquals("world", Files.readString(extractDir.resolve("xxx/sub/bbb.txt")));
    }

    @Test
    void testWindowsBackslashSeparators() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx\\sub\\aaa.txt", "backslash");
        Path zipFile = createTestZip("backslash.zip", entries);
        Path extractDir = tmp.resolve("extract-backslash");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("xxx/sub/aaa.txt")));
        assertEquals("backslash", Files.readString(extractDir.resolve("xxx/sub/aaa.txt")));
    }

    @Test
    void testWindowsBackslashRedundantWrapper() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx\\xxx\\aaa.txt", "backslash-redundant");
        Path zipFile = createTestZip("backslash-redundant.zip", entries);
        Path extractDir = tmp.resolve("extract-backslash-redundant");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertFalse(Files.exists(extractDir.resolve("xxx/xxx/aaa.txt")));
        assertEquals("backslash-redundant",
            Files.readString(extractDir.resolve("xxx/aaa.txt")));
    }

    @Test
    void testTraversalDotsSlashesRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil1", "../../evil.txt"));
    }

    @Test
    void testTraversalWindowsDotsSlashesRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil2", "..\\..\\evil.txt"));
    }

    @Test
    void testTraversalPrefixedRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil3", "xxx/../../evil.txt"));
    }

    @Test
    void testTraversalWindowsPrefixedRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil4", "xxx\\..\\..\\evil.txt"));
    }

    @Test
    void testAbsoluteUnixPathRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil5", "/evil.txt"));
    }

    @Test
    void testAbsoluteWindowsDrivePathRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil6", "C:/evil.txt"));
    }

    @Test
    void testAbsoluteWindowsBackslashDrivePathRejected() {
        assertThrows(Exception.class,
            () -> extractTo("evil7", "C:\\evil.txt"));
    }

    @Test
    void testDirectoryEntriesCreatedCorrectly() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/", null);
        entries.put("xxx/sub/", null);
        entries.put("xxx/sub/aaa.txt", "nested");
        Path zipFile = createTestZip("dirs.zip", entries);
        Path extractDir = tmp.resolve("extract-dirs");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.isDirectory(extractDir.resolve("xxx")));
        assertTrue(Files.isDirectory(extractDir.resolve("xxx/sub")));
        assertTrue(Files.exists(extractDir.resolve("xxx/sub/aaa.txt")));
    }

    @Test
    void testEmptyDirectoryArchive() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("emptydir/", null);
        Path zipFile = createTestZip("empty-dir.zip", entries);
        Path extractDir = tmp.resolve("extract-empty-dir");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.isDirectory(extractDir.resolve("emptydir")));
    }

    @Test
    void testOnlyRootDirectoryEntriesNoMeaningfulContent() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/", null);
        entries.put("xxx/xxx/", null);
        Path zipFile = createTestZip("only-dirs.zip", entries);
        Path extractDir = tmp.resolve("extract-only-dirs");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.isDirectory(extractDir.resolve("xxx")));
        assertTrue(Files.isDirectory(extractDir.resolve("xxx/xxx")));
    }

    @Test
    void testDuplicateSlashesNormalized() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx//sub//aaa.txt", "dup-slash");
        Path zipFile = createTestZip("dup-slash.zip", entries);
        Path extractDir = tmp.resolve("extract-dup-slash");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("xxx/sub/aaa.txt")));
        assertEquals("dup-slash", Files.readString(extractDir.resolve("xxx/sub/aaa.txt")));
    }

    @Test
    void testLeadingDotSlashNormalized() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("./xxx/aaa.txt", "dot-slash");
        Path zipFile = createTestZip("dot-slash.zip", entries);
        Path extractDir = tmp.resolve("extract-dot-slash");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt")));
        assertEquals("dot-slash", Files.readString(extractDir.resolve("xxx/aaa.txt")));
    }

    @Test
    void testTrailingSlashTreatedAsDirectory() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/aaa.txt/", null);
        entries.put("xxx/aaa.txt/bbb.txt", "trailing");
        Path zipFile = createTestZip("trailing.zip", entries);
        Path extractDir = tmp.resolve("extract-trailing");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.isDirectory(extractDir.resolve("xxx/aaa.txt")));
        assertTrue(Files.exists(extractDir.resolve("xxx/aaa.txt/bbb.txt")));
    }

    @Test
    void testFileContentPreserved() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/data.txt", "Hello, World!");
        Path zipFile = createTestZip("content.zip", entries);
        Path extractDir = tmp.resolve("extract-content");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertEquals("Hello, World!",
            Files.readString(extractDir.resolve("xxx/data.txt")));
    }

    @Test
    void testWindowsLineEndingsNormalized() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xxx/crlf.txt", "line1\r\nline2\r\nline3");
        Path zipFile = createTestZip("crlf.zip", entries);
        Path extractDir = tmp.resolve("extract-crlf");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        String content = Files.readString(extractDir.resolve("xxx/crlf.txt"));
        assertFalse(content.contains("\r"), "CR characters should be removed");
        assertTrue(content.contains("line1\nline2\nline3"));
    }

    @Test
    void testRedundantWrapperWithMultipleFiles() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("archive/", null);
        entries.put("archive/archive/", null);
        entries.put("archive/archive/file1.txt", "one");
        entries.put("archive/archive/file2.txt", "two");
        entries.put("archive/archive/sub/", null);
        entries.put("archive/archive/sub/file3.txt", "three");
        Path zipFile = createTestZip("multi-redundant.zip", entries);
        Path extractDir = tmp.resolve("extract-multi-redundant");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("archive/file1.txt")));
        assertTrue(Files.exists(extractDir.resolve("archive/file2.txt")));
        assertTrue(Files.exists(extractDir.resolve("archive/sub/file3.txt")));
        assertFalse(Files.exists(extractDir.resolve("archive/archive/file1.txt")));
        assertEquals("one", Files.readString(extractDir.resolve("archive/file1.txt")));
        assertEquals("two", Files.readString(extractDir.resolve("archive/file2.txt")));
        assertEquals("three", Files.readString(extractDir.resolve("archive/sub/file3.txt")));
    }

    @Test
    void testLegitimateNestedDirNotFlattened() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("project/", null);
        entries.put("project/src/", null);
        entries.put("project/src/main/", null);
        entries.put("project/src/main/App.java", "code");
        entries.put("project/README.md", "readme");
        Path zipFile = createTestZip("legit-nested.zip", entries);
        Path extractDir = tmp.resolve("extract-legit-nested");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertTrue(Files.exists(extractDir.resolve("project/src/main/App.java")));
        assertTrue(Files.exists(extractDir.resolve("project/README.md")));
        assertEquals("code", Files.readString(extractDir.resolve("project/src/main/App.java")));
    }

    @Test
    void testRootFileWithRedundantSiblingDoesNotFlatten() throws Exception {
        Path extractDir = extractTo("root-and-redundant",
            "aaa.txt", "xxx/xxx/bbb.txt");
        assertTrue(Files.exists(extractDir.resolve("aaa.txt")));
        assertTrue(Files.exists(extractDir.resolve("xxx/xxx/bbb.txt")));
        assertFalse(Files.exists(extractDir.resolve("xxx/bbb.txt")));
    }

    @Test
    void testEmptyArchive() throws Exception {
        Path zipFile = createTestZip("empty.zip");
        Path extractDir = tmp.resolve("extract-empty");
        Files.createDirectories(extractDir);
        callExtractZipSafely(zipFile, extractDir);

        assertFalse(Files.exists(extractDir.resolve("any-file")));
    }
}
