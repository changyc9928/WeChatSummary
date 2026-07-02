package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZipExtractionService {

    private final StorageConfig storageConfig;

    public String upload(MultipartFile file) throws IOException {
        Files.createDirectories(storageConfig.getUploadDir());

        Path tempZip = Files.createTempFile("upload-", ".zip");
        file.transferTo(tempZip);

        String uuid = UUID.randomUUID().toString();
        Path extractDir = storageConfig.getUploadDir().resolve(uuid);

        // 在这里打印日志，能同时看到文件名、生成的 UUID 以及解压目标路径
        log.info("Successfully received file [{}]. Generated UUID: [{}], extracting to: [{}]",
            file.getOriginalFilename(), uuid, extractDir.toAbsolutePath());

        Files.createDirectories(extractDir);

        try {
            extractZipSafely(tempZip, extractDir);
            log.info("Successfully extracted zip file for UUID: [{}]", uuid);
        } catch (IOException e) {
            log.error("Failed to extract zip file for UUID: [{}]", uuid, e);
            throw e;
        } finally {
            // 确保即使解压失败，临时文件也会被清理
            Files.deleteIfExists(tempZip);
        }

        return uuid;
    }

    private void extractZipSafely(Path zipFile, Path targetDir) throws IOException {
        try (
            InputStream fis = Files.newInputStream(zipFile);
            BufferedInputStream bis = new BufferedInputStream(fis);
            ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)
        ) {

            ZipArchiveEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // 剥离压缩包内的第一层目录 (如把 "x/file.txt" 变成 "file.txt")
                String strippedName = stripFirstDirectory(entryName);
                if (strippedName.isEmpty()) {
                    // 如果剥离后为空，说明这个 entry 本身就是那个顶层文件夹，直接跳过
                    continue;
                }

                Path resolvedPath = targetDir.resolve(strippedName).normalize();

                // Prevent Zip Slip vulnerability
                if (!resolvedPath.startsWith(targetDir)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);

                    // Optional: normalize line endings
                    normalizeTextFile(resolvedPath);
                }
            }
        }
    }

    /**
     * 剥离路径中的第一层目录 例如: "x/y/file.txt" -> "y/file.txt" "x/"           -> ""
     */
    private String stripFirstDirectory(String path) {
        // 统一将反斜杠替换为斜杠，防止 Windows 压缩包格式不一致
        path = path.replace("\\", "/");
        int firstSlash = path.indexOf('/');
        if (firstSlash != -1) {
            return path.substring(firstSlash + 1);
        }
        return path;
    }

    private void normalizeTextFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // Convert CRLF -> LF
            content = content.replace("\r\n", "\n");
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // ignore binary files
        }
    }
}