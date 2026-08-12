package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.util.InputSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final AgentOrchestrationService agentOrchestrationService;

    public TestController(AgentOrchestrationService agentOrchestrationService) {
        this.agentOrchestrationService = agentOrchestrationService;
    }

    /**
     * Agent 对话入口（LangGraph4j 图编排）
     *
     * <p>请求经输入消毒后，交由 AgentOrchestrationService.orchestrate() 执行 StateGraph 编排。
     * 图内 agent 节点流式推送 chunks，tools 节点执行工具调用，条件边控制 ReAct 循环。</p>
     *
     * @param sessionId 会话 ID
     * @param message   用户问题
     * @return 流式编排结果
     */
    @GetMapping(value = "/agent/{sessionId}/{message}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> agent(@PathVariable String sessionId, @PathVariable String message) {
        String safeMessage = InputSanitizer.validate(message);
        log.info("[Agent入口] 会话[{}] 收到问题：{}", sessionId, safeMessage);
        return agentOrchestrationService.orchestrate(sessionId, safeMessage);
    }
}
