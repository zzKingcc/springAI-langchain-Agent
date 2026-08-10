package com.zxcSpringAI.config;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.tools.Tools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 编排配置
 *
 * <p>构建 Agent 编排服务，注册 Tools 工具集，使用独立的高推理能力 LLM 进行 Tool 规划。
 * 编排任务为同步推理，使用普通 ChatLanguageModel 而非流式模型。
 * 模型暂未指定（后期手动配置），当前与对话入口共用 openAiChatModel。</p>
 */
@Configuration
public class AgentOrchestrationConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationConfig.class);

    /** 工具实例 */
    private final Tools tools = new Tools();

    @Bean
    public AgentOrchestrationService agentOrchestrationService(
            // TODO: 后期替换为独立的强推理模型（如 qwen-max）
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Qualifier("chatMemoryProvider") ChatMemoryProvider chatMemoryProvider) {

        log.info("[Agent编排] 初始化完成，已注册 {} 个工具方法，模型待指定",
                tools.getClass().getDeclaredMethods().length);

        return AiServices.builder(AgentOrchestrationService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tools)
                .build();
    }
}