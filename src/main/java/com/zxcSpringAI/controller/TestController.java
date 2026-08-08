package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.MyAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 知识问答接口
 *
 * <p>基于组合检索器（向量 Top15 + 关键词 Top5）的 RAG 问答能力：</p>
 * <ol>
 *   <li>用户问题经由 {@link MyAIService} 自动路由到 {@code CompositeContentRetriever} 执行混合检索；</li>
 *   <li>检索命中的知识片段与对话历史由 {@code @AiService} 自动组装为 Prompt；</li>
 *   <li>流式对话模型按分块返回生成文本。</li>
 * </ol>
 *
 * <p>检索逻辑由 {@code @AiService(contentRetriever = "myContentRetriever")} 声明式注入，
 * Controller 层无需手动调用检索器，避免重复查询。</p>
 *
 * <p>会话记忆通过 {@code sessionId} 隔离，Redis 持久化，支持多轮对话。</p>
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    @Autowired
    private MyAIService myAIService;

    /**
     * 问答接口（流式输出）
     *
     * <p>检索 → Prompt 组装 → 模型调用 全部由 {@link MyAIService} 内部自动完成，
     * Controller 仅负责接收请求并返回流式响应。</p>
     *
     * @param sessionId 会话 ID，用于多轮对话记忆隔离（同一 sessionId 共享上下文）
     * @param message 用户问题
     * @return 流式文本回答（按 DashScope 流式模型分块返回）
     */
    @GetMapping(value = "/message/{sessionId}/{message}", produces = "text/html;charset=UTF-8")
    public Flux<String> getMessage(@PathVariable String sessionId, @PathVariable String message) {
        log.info("[问答接口] 会话[{}] 收到问题：{}", sessionId, message);
        return myAIService.chat(sessionId, message);
    }
}
