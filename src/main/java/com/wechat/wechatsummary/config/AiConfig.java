package com.wechat.wechatsummary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration layer initializing Spring AI 2.0 foundational component infrastructures. Provisions
 * basic large language model connection options, deep timeout buffers to prevent mid-chunk
 * closures, specialized multi-modal vision configurations, and standard JSON object mappers.
 */
@Configuration
public class AiConfig {

    /**
     * Initializes the default system ChatClient interface wrapper. Sets a massive 1-hour connection
     * request timeout window to securely handle complex, long-running rolling transcript
     * executions.
     *
     * @param chatModel the underlying primary Spring AI chat connection model driver
     * @return a thread-safe configured ChatClient instance
     */
    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultOptions(OpenAiChatOptions.builder()
                .timeout(Duration.ofHours(1)))
            .build();
    }

    /**
     * Standardizes a globally accessible ObjectMapper configuration layer. Automatically scans and
     * registers active class module dependencies such as JavaTime modules for smooth ISO date
     * conversions.
     *
     * @return a normalized, module-aware ObjectMapper instance
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    // === 1. Base Multi-Modal Vision Low-Level Model Driver ===

    /**
     * Provisions the structural OpenAI chat model layer utilized for processing heavy visual tasks.
     * Configured via custom external runtime variables to connect seamlessly to third-party
     * endpoints or proxy structures.
     *
     * @param baseUrl API proxy gateway endpoint path configured for multimodal tasks
     * @param apiKey  secure credential token string required to pass proxy firewall constraints
     * @param model   specific destination target vision language model handle configuration string
     * @return an explicit OpenAiChatModel infrastructure driver instance
     */
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

    // === 2. High-Level Multi-Modal ChatClient Abstraction Layer ===

    /**
     * Wraps the raw low-level vision model instance into Spring AI's sleek, fluid ChatClient API.
     * Named explicitly to allow business logic controllers or services to request this model
     * cleanly using standard dependency injection qualifiers.
     *
     * @param multimodalChatModel the low-level base vision framework driver bean
     * @return a fluent ChatClient abstraction interface specifically allocated for visual assets
     */
    @Bean(name = "multimodalChatClient")
    public ChatClient multimodalChatClient(OpenAiChatModel multimodalChatModel) {
        return ChatClient.builder(multimodalChatModel)
            // Optional baseline injection point configuration examples:
            // .defaultSystem("You are a multi-modal WeChat analysis engine expert...")
            .build();
    }
}