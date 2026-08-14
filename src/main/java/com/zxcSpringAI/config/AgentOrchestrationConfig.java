package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.memory.CancellationTracker;
import com.zxcSpringAI.memory.RedisCheckpointSaver;
import com.zxcSpringAI.model.AiPromptProperties;
import com.zxcSpringAI.tools.Tools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 编排配置
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
            AiPromptProperties promptProperties,
            RedisCheckpointSaver checkpointSaver,
            ObjectStreamStateSerializer<MessagesState<ChatMessage>> graphStateSerializer,
            CancellationTracker cancellationTracker) {

        String systemMessage = promptProperties.getSystemMessage();
        log.info("[Agent编排配置] 创建 AgentOrchestrationService，流式模型=qwen3.7-plus，会话记忆=Redis持久化，检查点=Redis(支持 interrupt/resume)，支持任务停止，系统提示词 {} 字符",
                systemMessage != null ? systemMessage.length() : 0);

        return new AgentOrchestrationService(streamingChatModel, chatMemoryProvider, tools, systemMessage, checkpointSaver, graphStateSerializer, cancellationTracker);
    }
}
