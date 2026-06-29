package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatAnalysisService {

    private final ChatClient chatClient;
    // 💡 注入已有的配置类
    private final StorageConfig storageConfig;

    /**
     * 根据 UUID 自动寻找对应的 processed.md 文件，并让 LLM 进行深度吃瓜分析
     *
     * @param uuid 任务或文件的 UUID
     * @return 大模型的分析结果
     */
    public String analyzeChatLog(UUID uuid) {
        // 1. 利用 StorageConfig 优雅地拼接目标文件夹路径：{storage.uploadDir}/outputs
        Path outputsDir = storageConfig.getUploadDir().resolve("outputs");
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";

        try {
            // 2. 在 outputs 目录下寻找前缀为 UUID 且以 _processed.md 结尾的文件
            Path targetFilePath;
            try (var stream = Files.list(outputsDir)) {
                targetFilePath = stream
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(filePrefix) && fileName.endsWith(fileSuffix);
                    })
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                        "未找到对应的清洗后 Markdown 文件。UUID: " + filePrefix + " 期待后缀: "
                            + fileSuffix
                    ));
            }

            // 3. 读取寻找到的 Markdown 文件内容
            String chatLogContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);

            // 4. 定义系统提示词 (System Prompt)，赋予大模型角色和规则
            String systemPrompt = """
                请帮我用编年史文体总结群聊信息，别漏了把高光时刻都写出来
                """;

            // 5. 调用 Spring AI 发起流畅的 Fluent API 请求
            return chatClient.prompt()
                .system(systemPrompt) // 注入系统角色
                .user(u -> u.text("""
                        这是我的群聊记录文件内容：
                        ---
                        {content}
                        ---
                        """)
                    .param("content", chatLogContent)) // 动态注入聊天内容
                .call()
                .content(); // 获取返回的纯文本字符串

        } catch (IOException e) {
            throw new RuntimeException("读取或寻找聊天记录 Markdown 文件失败，UUID: " + uuid, e);
        }
    }
}