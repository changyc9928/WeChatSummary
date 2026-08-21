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

    /**
     * Directory that holds distributable tool binaries (e.g. the Windows
     * {@code bridge.exe} sidecar served by ToolController). Defaults to
     * {@code ./tools} so the Docker deployment can mount {@code ./tools:/app/tools}.
     */
    private Path toolsDir = Path.of("./tools");
}
