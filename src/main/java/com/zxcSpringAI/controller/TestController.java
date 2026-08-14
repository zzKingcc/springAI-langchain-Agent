package com.zxcSpringAI.controller;

import com.zxcSpringAI.aiService.AgentOrchestrationService;
import com.zxcSpringAI.memory.CancellationTracker;
import com.zxcSpringAI.util.InputSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final AgentOrchestrationService agentOrchestrationService;
    private final CancellationTracker cancellationTracker;

    public TestController(AgentOrchestrationService agentOrchestrationService,
                          CancellationTracker cancellationTracker) {
        this.agentOrchestrationService = agentOrchestrationService;
        this.cancellationTracker = cancellationTracker;
    }

    /**
     * Agent 对话入口（LangGraph4j 图编排,带断点状态机）
     *
     * <p>请求经输入消毒后，交由 AgentOrchestrationService.orchestrate() 执行 StateGraph 编排。
     * 图内 agent 节点流式推送 chunks，条件边按工具类型分流:
     * 自主工具直连 tools 节点,需授权工具触发 review 中断。</p>
     *
     * <p>当流式输出出现 __INTERRUPT__ 前缀事件时,表示等待人工授权,
     * 前端应弹审核 UI,用户决定后调 /test/agent/resume/{sessionId} 继续。</p>
     *
     * @param sessionId 会话 ID
     * @param message   用户问题
     * @return 流式编排结果,中断时以 __INTERRUPT__: 开头
     */
    @GetMapping(value = "/agent/{sessionId}/{message}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> agent(@PathVariable String sessionId, @PathVariable String message) {
        String safeMessage = InputSanitizer.validate(message);
        log.info("[Agent入口] 会话[{}] 收到问题：{}", sessionId, safeMessage);
        return agentOrchestrationService.orchestrate(sessionId, safeMessage);
    }

    /**
     * 恢复被中断的会话（人工授权/拒绝后调用）
     *
     * <p>当 orchestrate 流返回 __INTERRUPT__ 事件后,前端调用此接口:
     * <ul>
     *   <li>approved=true:批准执行待授权工具,graph 从 review 继续 → tools → agent</li>
     *   <li>approved=false:拒绝,注入拒绝反馈让 agent 重新生成不带工具调用的回复</li>
     * </ul>
     *
     * @param sessionId 会话 ID,需与触发中断时的 sessionId 一致
     * @param approved  true=批准,false=拒绝
     * @return 流式输出,resume 后的 agent 回复
     */
    @GetMapping(value = "/agent/resume/{sessionId}", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> resume(@PathVariable String sessionId, @RequestParam boolean approved) {
        log.info("[Agent入口] 会话[{}] resume 请求,approved={}", sessionId, approved);
        return agentOrchestrationService.resume(sessionId, approved);
    }

    /**
     * 停止正在执行的任务(不可恢复)
     *
     * <p>设置取消标志后立即返回。执行线程在 agentNode 的流式回调或 toolsNode 的工具执行前
     * 检测到标志后抛 CancellationException,orchestrate/resume 的 catch 块执行清理:
     * 回滚 UserMessage、清 checkpoint(不可 resume)、推送 __STOPPED__ 事件。</p>
     *
     * <p>幂等:对同一会话多次调用返回相同的 stopRequested=false(已处于停止状态)。</p>
     *
     * @param sessionId 会话 ID
     * @return {"sessionId":"...","stopRequested":true|false}
     */
    @PostMapping("/agent/stop/{sessionId}")
    public Map<String, Object> stop(@PathVariable String sessionId) {
        boolean triggered = cancellationTracker.requestStop(sessionId);
        log.info("[Agent入口] 会话[{}] stop 请求,stopRequested={}", sessionId, triggered);
        return Map.of("sessionId", sessionId, "stopRequested", triggered);
    }
}
