package com.zxcSpringAI.aiService;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 统一 Agent 对话服务接口
 */
public interface AgentOrchestrationService {

    @UserMessage("用户问题：{{message}}")
    TokenStream orchestrate(@MemoryId String sessionId, @V("message") String message);

}