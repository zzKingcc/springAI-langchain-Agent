package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.util.InputSanitizer;
import com.zxcSpringAI.util.TokenUsageTracker;
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
     * Agent 对话入口
     *
     * @param sessionId
     * @param message
     * @return 流式编排结果，模型自主选择工具并整合结果
     */
    @GetMapping(value = "/agent/{sessionId}/{message}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> agent(@PathVariable String sessionId, @PathVariable String message) {
        String safeMessage = InputSanitizer.validate(message);
        log.info("[Agent统一入口] 会话[{}] 收到问题：{}", sessionId, safeMessage);
        TokenUsageTracker.begin();
        return Flux.create(sink -> {
            agentOrchestrationService.orchestrate(sessionId, safeMessage)
                    .onPartialResponse(chunk -> {
                        TokenUsageTracker.recordLlmOutputChunk(chunk);
                        sink.next(chunk);
                    })
                    .onCompleteResponse(response -> {
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