package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.aiService.ChatEntryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 知识问答接口
 *
 * <p>对话入口：基于组合检索器（向量 Top15 + 关键词 Top5）的 RAG 问答能力，
 * 检索命中的知识片段与对话历史由 {@link ChatEntryService} 自动组装为 Prompt，
 * 流式对话模型按分块返回生成文本。</p>
 *
 * <p>Agent 编排：由 {@link AgentOrchestrationService} 负责 Tool 调用链编排与结果整合。</p>
 *
 * <p>会话记忆通过 {@code sessionId} 隔离，Redis 持久化，支持多轮对话。</p>
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    @Autowired
    private ChatEntryService chatEntryService;

    @Autowired
    private AgentOrchestrationService agentOrchestrationService;

    /**
     * 对话入口（流式输出）
     *
     * @param sessionId 会话 ID，用于多轮对话记忆隔离
     * @param message 用户问题
     * @return 流式文本回答
     */
    @GetMapping(value = "/message/{sessionId}/{message}", produces = "text/html;charset=UTF-8")
    public Flux<String> getMessage(@PathVariable String sessionId, @PathVariable String message) {
        log.info("[对话入口] 会话[{}] 收到问题：{}", sessionId, message);
        return chatEntryService.chat(sessionId, message);
    }

    /**
     * Agent 编排入口（同步返回）
     *
     * @param sessionId 会话 ID
     * @param message 用户指令
     * @return 编排结果
     */
    @GetMapping(value = "/agent/{sessionId}/{message}", produces = "text/html;charset=UTF-8")
    public String agent(@PathVariable String sessionId, @PathVariable String message) {
        log.info("[Agent编排] 会话[{}] 收到指令：{}", sessionId, message);
        return agentOrchestrationService.orchestrate(sessionId, message);
    }
}