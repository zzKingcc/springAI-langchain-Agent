package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.tools.Tools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;

/**
 * Agent 编排配置
 *
 * <p>构建 Agent 编排服务：使用已引入的千文模型（openAiChatModel），
 * 无会话记忆（每轮调用独立），仅绑定模型 + Tools。</p>
 */
@Configuration
public class AgentOrchestrationConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationConfig.class);

    @Bean
    public AgentOrchestrationService agentOrchestrationService(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            Tools tools) {

        int toolCount = 0;
        for (Method m : tools.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                toolCount++;
            }
        }
        log.info("[Agent编排] 初始化完成，已注册 {} 个 @Tool 工具方法，模型=qwen-plus（共用），无会话记忆",
                toolCount);

        return AiServices.builder(AgentOrchestrationService.class)
                .chatModel(chatModel)
                .tools(tools)
                .build();
    }
}
