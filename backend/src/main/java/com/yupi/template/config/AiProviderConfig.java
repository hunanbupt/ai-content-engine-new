package com.yupi.template.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiProviderConfig {

    // ==================== ChatModel ====================

    @Bean("primaryChatModel")
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
    public ChatModel primaryOllamaChatModel(OllamaChatModel ollamaChatModel) {
        return ollamaChatModel;
    }

    @Bean("primaryChatModel")
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
    public ChatModel primaryDashscopeChatModel(DashScopeChatModel dashScopeChatModel) {
        return dashScopeChatModel;
    }

    // ==================== EmbeddingModel ====================

    @Bean("primaryEmbeddingModel")
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
    public EmbeddingModel primaryOllamaEmbeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }

    @Bean("primaryEmbeddingModel")
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
    public EmbeddingModel primaryDashscopeEmbeddingModel(DashScopeEmbeddingModel dashScopeEmbeddingModel) {
        return dashScopeEmbeddingModel;
    }
}