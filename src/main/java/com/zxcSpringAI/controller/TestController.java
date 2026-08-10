package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.util.InputSanitizer;
import com.zxcSpringAI.util.TokenUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 统一 Agent 对话接口
 *
 * <p>融合 RAG 知识库检索 + Tool 工具调用 + 会话记忆，作为唯一的用户对话入口。
 * 流式输出，模型自主编排检索与工具调用顺序后逐块返回最终回答。</p>
 *
 * <p>会话记忆通过 {@code sessionId} 隔离，Redis 持久化，支持多轮对话。</p>
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final AgentOrchestrationService agentOrchestrationService;

    public TestController(AgentOrchestrationService agentOrchestrationService) {
        this.agentOrchestrationService = agentOrchestrationService;
    }

    /**
     * 统一 Agent 对话入口（流式输出）
     *
     * <p>Tool 调用循环期间不产生 token 流，模型完成所有工具调用后，
     * 最终整合回答逐块流式返回。</p>
     *
     * @param sessionId 会话 ID，用于多轮对话记忆隔离
     * @param message   用户问题
     * @return 流式编排结果（模型自主选择 @Tool 工具并整合结果）
     */
    @GetMapping(value = "/agent/{sessionId}/{message}", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> agent(@PathVariable String sessionId, @PathVariable String message) {
        String safeMessage = InputSanitizer.validate(message);
        log.info("[Agent统一入口] 会话[{}] 收到问题：{}", sessionId, safeMessage);
        TokenUsageTracker.begin();
        return Flux.create(sink -> {
            agentOrchestrationService.orchestrate(sessionId, safeMessage)
                    .onNext(chunk -> {
                        TokenUsageTracker.recordLlmOutputChunk(chunk);
                        sink.next(chunk);
                    })
                    .onComplete(() -> {
                        TokenUsageTracker.finishAndLog();
                        sink.complete();
                    })
                    .onError(error -> {
                        TokenUsageTracker.finishAndLog();
                        sink.error(error);
                    })
                    .start();
        });
    }
}