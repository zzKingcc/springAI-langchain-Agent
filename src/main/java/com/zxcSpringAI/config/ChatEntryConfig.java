package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.ChatEntryService;
import com.zxcSpringAI.model.AiPromptProperties;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话入口配置
 *
 * <p>系统提示词从 application.yaml（ai.prompt.system-message）读取，
 * 通过 systemMessageProvider 注入，修改后重启生效，无需重新编译。</p>
 */
@Configuration
@EnableConfigurationProperties(AiPromptProperties.class)
public class ChatEntryConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatEntryConfig.class);

    @Bean
    public ChatEntryService chatEntryService(
            @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider,
            @Qualifier("myContentRetriever") ContentRetriever contentRetriever,
            AiPromptProperties promptProperties) {

        String systemMessage = promptProperties.getSystemMessage();
        log.info("[对话入口] 初始化完成，系统提示词从 application.yaml 读取，{} 字符",
                systemMessage != null ? systemMessage.length() : 0);

        return AiServices.builder(ChatEntryService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(contentRetriever)
                .systemMessageProvider(memoryId -> systemMessage)
                .build();
    }
}