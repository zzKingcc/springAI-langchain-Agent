package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.model.AiPromptProperties;
import com.zxcSpringAI.tools.Tools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 编排配置
 *
 * <p>创建 AgentOrchestrationService Bean，注入流式模型、会话记忆、工具集和系统提示词。
 * 图的构建和编译在 AgentOrchestrationService 构造函数中完成。</p>
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

        String systemMessage = promptProperties.getSystemMessage();
        log.info("[Agent编排配置] 创建 AgentOrchestrationService，流式模型=qwen3.7-plus，会话记忆=Redis持久化，系统提示词 {} 字符",
                systemMessage != null ? systemMessage.length() : 0);

        return new AgentOrchestrationService(streamingChatModel, chatMemoryProvider, tools, systemMessage);
    }
}
