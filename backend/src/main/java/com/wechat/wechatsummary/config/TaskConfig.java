package com.wechat.wechatsummary.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "task")
@Component
@Data
public class TaskConfig {

    private Duration redisTtl = Duration.ofDays(1);
}
