package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.model.AiPromptProperties;
import com.zxcSpringAI.tools.Tools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;

/**
 * 统一 Agent 对话配置
 */
@Configuration
@EnableConfigurationProperties(AiPromptProperties.class)
public class AgentOrchestrationConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationConfig.class);

    @Bean
    public AgentOrchestrationService agentOrchestrationService(
            @Qualifier("openAiStreamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider,
            Tools tools,
            AiPromptProperties promptProperties) {

        int toolCount = 0;
        for (Method m : tools.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolCount++;
            }
        }

        String systemMessage = promptProperties.getSystemMessage();
        log.info("[Agent统一入口] 初始化完成，已注册 {} 个 @Tool，流式模型=qwen3.7-plus，会话记忆=Redis持久化，系统提示词 {} 字符",
                toolCount, systemMessage != null ? systemMessage.length() : 0);

        return AiServices.builder(AgentOrchestrationService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(memoryId -> systemMessage)
                .tools(tools)
                .build();
    }
}