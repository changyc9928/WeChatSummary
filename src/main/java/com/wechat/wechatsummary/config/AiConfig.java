package com.wechat.wechatsummary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class AiConfig {

    @Bean
    @Order(Integer.MIN_VALUE) // 赋予最高优先级
    public OpenAiHttpClientBuilderCustomizer openAiHttpClientTimeoutCustomizer() {
        return builder -> {
            // 强行插入一个最高权限的拦截器，物理改写 OkHttp 的运行时超时
            builder.interceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    return chain
                        // 强制改写当前这一棒的链条超时时间
                        .withConnectTimeout(15, TimeUnit.SECONDS)
                        .withReadTimeout(3600, TimeUnit.MINUTES) // 1小时
                        .withWriteTimeout(15, TimeUnit.SECONDS)
                        .proceed(chain.request());
                }
            });
        };
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    // === 1. 多模态底层模型驱动 (保持原样) ===
    @Bean(name = "multimodalChatModel")
    public OpenAiChatModel multimodalChatModel(
        @Value("${custom-ai.multimodal.base-url}") String baseUrl,
        @Value("${custom-ai.multimodal.api-key}") String apiKey,
        @Value("${custom-ai.multimodal.model}") String model) {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .build();

        return OpenAiChatModel.builder()
            .options(options)
            .build();
    }

    // === 2. 将多模态模型封装并输出为高级的 ChatClient ===
    @Bean(name = "multimodalChatClient") // 显式命名，方便业务层用 @Qualifier("multimodalChatClient") 注入
    public ChatClient multimodalChatClient(OpenAiChatModel multimodalChatModel) {

        // 传入刚才定义的 multimodalChatModel 驱动
        return ChatClient.builder(multimodalChatModel)
            // 你还可以在这里配置这个 Client 专属的默认行为，例如：
            // .defaultSystem("你是一个多模态微信助手，能够分析图片、音频和文本。")
            .build();
    }
}
