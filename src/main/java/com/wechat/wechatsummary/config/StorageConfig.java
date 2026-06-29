package com.wechat.wechatsummary.config;

import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "storage")
@Component
@Data
public class StorageConfig {
    private Path uploadDir;
}
