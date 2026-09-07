package com.wechat.wechatsummary;

import com.wechat.wechatsummary.config.CacheEvictionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableCaching
@EnableRetry
@EnableConfigurationProperties({CacheEvictionProperties.class})
@SpringBootApplication
public class WeChatSummaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeChatSummaryApplication.class, args);
    }

}
