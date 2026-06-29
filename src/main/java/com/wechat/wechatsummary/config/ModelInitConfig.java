package com.wechat.wechatsummary.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class ModelInitConfig {

    @Bean
    public CommandLineRunner initModel() {
        return args -> {
            log.info("🚀 开始自动初始化语音模型 (whisper-1)...");

            String url = "http://localhost:58080/models/apply";
            RestTemplate restTemplate = new RestTemplate();

            try {
                // 1. 设置请求头
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                // 2. 设置请求体
                Map<String, String> requestBody = new HashMap<>();
                requestBody.put("id", "whisper-1");

                HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

                // 3. 发送 POST 请求
                String response = restTemplate.postForObject(url, request, String.class);
                log.info("🎉 模型自动初始化成功！响应结果: {}", response);

            } catch (Exception e) {
                log.error("❌ 模型自动初始化失败，请检查模型服务是否启动！", e);
            }
        };
    }
}