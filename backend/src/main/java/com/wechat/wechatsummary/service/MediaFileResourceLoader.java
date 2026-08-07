package com.wechat.wechatsummary.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

/**
 * Loads on-disk media files as Spring {@link Resource} instances with a probed content type,
 * falling back to a caller-supplied default when the type cannot be determined.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaFileResourceLoader {

    public Optional<MediaFileResource> load(String filePath, String fallbackContentType) {
        if (filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            Resource resource = new UrlResource(path.toUri());
            String contentType = Files.probeContentType(path);
            if (contentType == null) {
                contentType = fallbackContentType;
            }
            return Optional.of(new MediaFileResource(resource, contentType));
        } catch (Exception e) {
            log.error("Failed to load media file resource at path: {}", path, e);
            return Optional.empty();
        }
    }

    public record MediaFileResource(Resource resource, String contentType) {
    }
}