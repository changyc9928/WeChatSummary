package com.wechat.wechatsummary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "summary")
@Component
@Data
public class SummaryConfig {

    private int chunkSizeChars = 15000;
    private double progressCap = 99.9;
}
