package com.wechat.wechatsummary.service;

import com.openai.errors.RateLimitException;
import com.wechat.wechatsummary.config.StorageConfig;
import com.wechat.wechatsummary.repository.ChatAnalysisTaskRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ChatAnalysisService {

    private static final int MAX_CHUNK_CHARS = 15000;

    // 用来抓取 <summary> 标签内真正总结的正则表达式
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("<summary>(.*?)</summary>",
        Pattern.DOTALL);

    private final ChatClient chatClient;
    private final StorageConfig storageConfig;
    private final ChatAnalysisTaskRepository taskRepository;
    private final ChatAnalysisService self;

    public ChatAnalysisService(
        @Qualifier("chatClientBuilder") ChatClient.Builder chatClientBuilder,
        StorageConfig storageConfig,
        ChatAnalysisTaskRepository taskRepository,
        @Lazy ChatAnalysisService self) {
        this.chatClient = chatClientBuilder.build();
        this.storageConfig = storageConfig;
        this.taskRepository = taskRepository;
        this.self = self;
    }

    @Async
    public void analyzeChatLogAsync(UUID uuid) {
        log.info("【Spring AI 2.0 防污染断点续传线程启动】UUID: {}", uuid);

        Path outputsDir = storageConfig.getUploadDir().resolve("outputs");
        String filePrefix = uuid.toString();
        String fileSuffix = "_processed.md";
        Path tempProgressPath = outputsDir.resolve(uuid + "_analysis.temp");

        try {
            Path targetFilePath;
            try (var stream = Files.list(outputsDir)) {
                targetFilePath = stream
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(filePrefix) && fileName.endsWith(fileSuffix);
                    })
                    .findFirst()
                    .orElseThrow(
                        () -> new RuntimeException("未找到对应的清洗后文件：" + filePrefix));
            }

            String rawContent = Files.readString(targetFilePath, StandardCharsets.UTF_8);
            List<String> chunks = splitContent(rawContent);
            if (chunks.isEmpty()) {
                log.warn("UUID: {} 文件内容为空", uuid);
                taskRepository.updateStatusToFailed(uuid, "清洗后的聊天记录文件内容为空");
                return;
            }

            int startIndex = 0;
            String previousContextSummary = "【前情提要：这是聊天的开端】";

            if (Files.exists(tempProgressPath)) {
                try {
                    List<String> lines = Files.readAllLines(tempProgressPath,
                        StandardCharsets.UTF_8);
                    if (!lines.isEmpty()) {
                        // 【注意】以前你的逻辑是 startIndex = lines.get(0) + 1
                        // 为了配合“失败不前进”的逻辑，我们这里依然维持读取：
                        // 如果上次成功写了进度 X，说明 X 块已经做完，本次从 X + 1 开始。
                        startIndex = Integer.parseInt(lines.get(0).trim()) + 1;
                        previousContextSummary = String.join("\n", lines.subList(1, lines.size()));
                        log.info("📢 【检测到断点】UUID: {} 上次成功到第 {} 块。本次从第 {} 块继续！",
                            uuid, startIndex, startIndex + 1);
                    }
                } catch (Exception e) {
                    log.warn("UUID: {} 读取临时进度文件失败，将从头开始...", uuid, e);
                    startIndex = 0;
                    previousContextSummary = "流水账开端";
                }
            }

            if (startIndex >= chunks.size()) {
                log.info("UUID: {} 检测到进度已全部跑完，直接进入结果落盘阶段。", uuid);
            } else {
                log.info("UUID: {} 开始/恢复滚动分析，总块数: {}，当前起始块: {}", uuid,
                    chunks.size(), startIndex + 1);

                for (int i = startIndex; i < chunks.size(); i++) {
                    log.info("UUID: {} 正在处理第 ({}/{}) 块...", uuid, i + 1, chunks.size());
                    String chunk = chunks.get(i);

                    // 1. 调用带重试的方法。如果 10 次全挂，这里会直接抛出 RuntimeException
                    // 从而直接杀出 for 循环，进入外层的 catch(Exception e) 块！
                    String rawModelOutput = self.callChatClientWithRetry(previousContextSummary,
                        chunk);

                    // 2. 🧼 核心清洗：利用正则把 <summary> 里的金子捞出来
                    Matcher matcher = SUMMARY_PATTERN.matcher(rawModelOutput);
                    String cleanSummary;
                    if (matcher.find()) {
                        cleanSummary = matcher.group(1).trim();
                    } else {
                        log.warn("UUID: {} 第 {} 块未匹配到 <summary> 标签，触发防污染降级截断！",
                            uuid, i + 1);
                        cleanSummary = rawModelOutput.replaceAll("<.*?>", "").trim();
                        if (cleanSummary.contains("Wait 600")) {
                            cleanSummary = cleanSummary.split("(?i)wait\\s+\\d+")[0].trim();
                        }
                    }

                    // 3. 只有成功拿到 LLM 结果并解析后，才更新内存指针并持久化进度文件！
                    previousContextSummary = cleanSummary;
                    log.info("UUID: {} 第 ({}/{}) 块处理完毕，清洗后提要字数: {}", uuid, i + 1,
                        chunks.size(), previousContextSummary.length());

                    // 保存当前成功处理完的块索引 i
                    String tempFileContent = i + "\n" + previousContextSummary;
                    Files.writeString(tempProgressPath, tempFileContent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

            // =======================================================
            // 3. 最终结果落盘
            // =======================================================
            String finalReport = previousContextSummary;

            Path resultTxtPath = outputsDir.resolve(uuid + "_summary.txt");
            Files.writeString(resultTxtPath, finalReport, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Files.deleteIfExists(tempProgressPath);
            log.info("【完美搞定】UUID: {} 任务全量完成。安全落盘: {}，临时缓存已清除。", uuid,
                resultTxtPath.toAbsolutePath());

            taskRepository.updateResult(uuid, resultTxtPath.toAbsolutePath().toString());
            taskRepository.updateStatusToSuccess(uuid);

        } catch (Exception e) {
            // 当 LLM 彻底挂掉或网络中断时，会走到这里：
            // 此时由于没有推进 i，也没有重写 tempProgressPath 里的索引
            // 下次再进来时，依然会从最后一次成功的 [i + 1] 块（即本次失败的这一块）重新开始！
            log.error(
                "【错误异常】UUID: {} 后台分析线程断开。别慌，进度已安全保留在最后一次成功的断点！",
                uuid, e);
            taskRepository.updateStatusToFailed(uuid,
                "分析在后台中断（已保留断点）：" + e.getMessage());
        }
    }

    /**
     * ✨ 针对聊天分析 ChatClient 请求进行独立高弹性重试
     */
    @Retryable(
        retryFor = {RateLimitException.class,
            org.springframework.web.client.HttpServerErrorException.class},
        maxAttempts = 10,
        backoff = @Backoff(delay = 30000, maxDelay = 3600000, multiplier = 2.0, random = true)
    )
    public String callChatClientWithRetry(String historyContext, String currentChunk) {
        log.info("Calling Spring AI ChatClient for Chat Analysis...");

        String refineSystemPrompt = "（保持你原本的 Prompt 不变...）";
        String userPrompt = "（保持你原本的 Prompt 不变...）";

        try {
            return chatClient.prompt()
                .system(refineSystemPrompt)
                .user(user -> user.text(userPrompt)
                    .param("historyContext", historyContext)
                    .param("currentChunk", currentChunk))
                .call()
                .content();
        } catch (Exception e) {
            log.error("Failed to execute AI request via ChatClient", e);
            throw new RuntimeException("LLM request failed via ChatClient: " + e.getMessage(), e);
        }
    }

    // 🔴 【核心修改】删掉了原本的 @Recover 方法！
    // 不再进行假装成功的假兜底，让异常直接抛出给主流程捕获，从而实现真正的“卡在当前断点”。

    private List<String> splitContent(String content) {
        List<String> chunks = new ArrayList<>();
        int length = content.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + MAX_CHUNK_CHARS, length);
            if (end < length) {
                int nextNewLine = content.lastIndexOf('\n', end);
                if (nextNewLine > start) {
                    end = nextNewLine;
                }
            }
            chunks.add(content.substring(start, end).trim());
            start = end + 1;
        }
        return chunks;
    }
}