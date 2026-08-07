package com.wechat.wechatsummary.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "http")
@Component
@Data
public class HttpConfig {

    private Duration cacheMaxAge = Duration.ofHours(1);
}
