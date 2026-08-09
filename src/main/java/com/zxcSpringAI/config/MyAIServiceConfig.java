package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.MyAIService;
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
 * MyAIService 编程式构建配置
 *
 * <p>系统提示词从 application.yaml（ai.prompt.system-message）读取，
 * 通过 systemMessageProvider 注入，修改后重启生效，无需重新编译。</p>
 */
@Configuration
@EnableConfigurationProperties(AiPromptProperties.class)
public class MyAIServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(MyAIServiceConfig.class);

    @Bean
    public MyAIService myAIService(
            @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider,
            @Qualifier("myContentRetriever") ContentRetriever contentRetriever,
            AiPromptProperties promptProperties) {

        String systemMessage = promptProperties.getSystemMessage();
        log.info("[AI服务] 初始化完成，系统提示词从 application.yaml 读取，{} 字符",
                systemMessage != null ? systemMessage.length() : 0);

        return AiServices.builder(MyAIService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(contentRetriever)
                .systemMessageProvider(memoryId -> systemMessage)
                .build();
    }
}