package com.wechat.wechatsummary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "processing")
@Component
@Data
public class ProcessingConfig {

    private long imageMaxSizeBytes = 5_000_000;
    private int logProgressEvery = 500;
}
