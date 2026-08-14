package com.zxcSpringAI.aiService;

import com.zxcSpringAI.memory.CancellationTracker;
import com.zxcSpringAI.memory.DualConstraintChatMemory;
import com.zxcSpringAI.tools.RequireApproval;
import com.zxcSpringAI.tools.Tools;
import com.zxcSpringAI.util.TokenUsageTracker;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.bsc.langgraph4j.langchain4j.serializer.std.ChatMesssageSerializer;
import org.bsc.langgraph4j.langchain4j.serializer.std.ToolExecutionRequestSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.StateSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/**
 * 基于 LangGraph4j 的 Agent 编排服务(带断点状态机)
 *
 * <p>图结构:START → agent → (条件边: 工具类型?) ─┬→ exit → END
 *                                              ├→ auto → tools → agent (循环, 自主工具直连不中断)
 *                                              └→ review(中断) → tools → agent (需授权工具, 人工 approve 后继续)</p>
 *
 * <p>中断机制:review 节点前通过 CompileConfig.interruptBefore 暂停,checkpoint 落 Redis。
 * 前端收到 __INTERRUPT__ 事件后弹审核 UI,用户 approve 调 resume 方法继续执行。</p>
 */
@Slf4j
public class AgentOrchestrationService {
    /** SSE 中断事件前缀,前端据此识别"需人工授权" */
    public static final String INTERRUPT_EVENT = "__INTERRUPT__:";
    /** SSE 停止事件,前端据此识别"任务已被用户停止" */
    public static final String STOP_EVENT = "__STOPPED__";

    private final CompiledGraph<MessagesState<ChatMessage>> compiledGraph;
    private final ChatMemoryProvider chatMemoryProvider;
    private final StreamingChatModel streamingChatModel;
    private final List<ToolSpecification> toolSpecs;
    private final Map<String, ToolExecutor> toolExecutors;
    private final String systemMessage;
    /** 需授权工具名集合(贴了 @RequireApproval 注解的工具) */
    private final Set<String> toolsRequiringApproval;
    private final CancellationTracker cancellationTracker;
    private final BaseCheckpointSaver checkpointSaver;

    /** 按线程 ID 存储流式 sink,供 agent 节点推送 chunks */
    @SuppressWarnings("deprecation")
    private static final ConcurrentHashMap<Long, FluxSink<String>> STREAMING_SINKS = new ConcurrentHashMap<>();
    /** 按线程 ID 存 sessionId,供中断检查和停止清理使用 */
    private static final ConcurrentHashMap<Long, String> THREAD_SESSION_IDS = new ConcurrentHashMap<>();
    /** 按线程 ID 累积半截文本,停止时用于日志记录(不写回记忆) */
    private static final ConcurrentHashMap<Long, StringBuilder> PARTIAL_OUTPUTS = new ConcurrentHashMap<>();

    private static long currentThreadId() {
        return Thread.currentThread().getId();
    }

    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            Tools tools,
            String systemMessage,
            BaseCheckpointSaver checkpointSaver,
            CancellationTracker cancellationTracker) {

        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.systemMessage = systemMessage;
        this.checkpointSaver = checkpointSaver;
        this.cancellationTracker = cancellationTracker;

        // 从 Tools 对象提取 @Tool 规格和执行器,同步判断是否需授权
        this.toolSpecs = new ArrayList<>();
        this.toolExecutors = new HashMap<>();
        this.toolsRequiringApproval = new HashSet<>();
        for (Method m : tools.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                toolSpecs.add(spec);
                toolExecutors.put(spec.name(), new DefaultToolExecutor(tools, m));
                if (m.isAnnotationPresent(RequireApproval.class)) {
                    toolsRequiringApproval.add(spec.name());
                }
            }
        }

        this.compiledGraph = buildGraph(checkpointSaver);

        log.info("[Agent编排-LangGraph] 初始化完成,已注册 {} 个工具(其中 {} 个需授权),图结构: agent→(auto|review)→tools→agent(条件循环)",
                toolSpecs.size(), toolsRequiringApproval.size());
    }

    /**
     * 构建 StateGraph 并编译
     *
     * <p>三路条件边:
     * <ul>
     *   <li>"exit" → END:无工具调用</li>
     *   <li>"auto" → tools:全自主工具,直连不中断</li>
     *   <li>"review" → review 节点:含需授权工具,interruptBefore 暂停</li>
     * </ul>
     */
    private CompiledGraph<MessagesState<ChatMessage>> buildGraph(BaseCheckpointSaver checkpointSaver) {
        try {
            var stateSerializer = new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new);
            stateSerializer.mapper()
                    .register(ToolExecutionRequest.class, new ToolExecutionRequestSerializer())
                    .register(ChatMessage.class, new ChatMesssageSerializer());

            var workflow = new MessagesStateGraph<ChatMessage>(stateSerializer)
                    .addNode("agent", syncNode(this::agentNode))
                    .addNode("review", syncNode(s -> Map.of()))   // no-op 节点,仅作中断锚点
                    .addNode("tools", syncNode(this::toolsNode))
                    .addEdge(START, "agent")
                    .addConditionalEdges("agent", edge_async(this::routeAfterAgent),
                            Map.of("exit", END, "auto", "tools", "review", "review"))
                    .addEdge("review", "tools")   // approve 后执行工具
                    .addEdge("tools", "agent");

            var compileConfig = org.bsc.langgraph4j.CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .interruptBefore("review")    // 仅 review 节点前中断,tools 直连不暂停
                    .releaseThread(true)          // HITL 用完即弃
                    .build();

            return workflow.compile(compileConfig);
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException("StateGraph 构建失败", e);
        }
    }

    /**
     * 同步节点包装器:在调用线程上执行节点逻辑,不切换线程
     *
     * <p>替代 node_async(),避免 ForkJoinPool 线程切换导致 ThreadLocal/线程ID 上下文丢失。</p>
     */
    private AsyncNodeAction<MessagesState<ChatMessage>> syncNode(
            java.util.function.Function<MessagesState<ChatMessage>, Map<String, Object>> action) {
        return state -> {
            try {
                return CompletableFuture.completedFuture(action.apply(state));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    /**
     * agent 节点:调用流式模型,推送 chunks 到 FluxSink,返回 AiMessage 到状态
     */
    private Map<String, Object> agentNode(MessagesState<ChatMessage> state) {
        long threadId = currentThreadId();
        FluxSink<String> sink = STREAMING_SINKS.get(threadId);
        String sessionId = THREAD_SESSION_IDS.get(threadId);
        AtomicInteger llmOutputTokens = new AtomicInteger(0);
        StringBuilder partialBuffer = new StringBuilder();
        PARTIAL_OUTPUTS.put(threadId, partialBuffer);

        try {
            var parameters = ChatRequestParameters.builder()
                    .toolSpecifications(toolSpecs)
                    .build();
            var request = ChatRequest.builder()
                    .messages(state.messages())
                    .parameters(parameters)
                    .build();

            CompletableFuture<AiMessage> future = new CompletableFuture<>();

            streamingChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    // 停止检查:用户请求停止后,完成 future 抛 CancellationException,终止流
                    if (cancellationTracker.isCancelled(sessionId)) {
                        future.completeExceptionally(
                                new CancellationException("用户主动停止"));
                        return;
                    }
                    if (sink != null) {
                        llmOutputTokens.addAndGet(TokenUsageTracker.estimateTokens(partialResponse));
                        partialBuffer.append(partialResponse);
                        sink.next(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    future.complete(completeResponse.aiMessage());
                }

                @Override
                public void onError(Throwable error) {
                    future.completeExceptionally(error);
                }
            });

            AiMessage aiMessage = future.join();
            TokenUsageTracker.addLlmOutputTokens(llmOutputTokens.get());
            return Map.of("messages", aiMessage);
        } finally {
            PARTIAL_OUTPUTS.remove(threadId);
        }
    }

    /**
     * tools 节点:执行 LLM 请求的工具调用,返回 ToolExecutionResultMessage 列表到状态
     */
    private Map<String, Object> toolsNode(MessagesState<ChatMessage> state) {
        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (!(lastMessage instanceof AiMessage aiMessage)) {
            throw new IllegalStateException("最后一条消息不是 AiMessage");
        }

        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            // 停止检查:每个工具执行前检查标志,避免停止后继续执行后续工具
            String sessionId = THREAD_SESSION_IDS.get(currentThreadId());
            if (cancellationTracker.isCancelled(sessionId)) {
                throw new CancellationException("用户主动停止");
            }
            ToolExecutor executor = toolExecutors.get(request.name());
            if (executor == null) {
                throw new IllegalStateException("未找到工具执行器: " + request.name());
            }
            log.info("[Agent编排-工具节点] 执行工具: {}", request.name());
            String result = executor.execute(request, null);
            results.add(ToolExecutionResultMessage.from(request, result));
        }

        return Map.of("messages", results);
    }

    /**
     * 条件边:agent 节点执行后,按工具调用类型三路分流
     *
     * @return "exit" 无工具→END; "auto" 全自主工具→tools直连; "review" 含需授权工具→review节点
     */
    private String routeAfterAgent(MessagesState<ChatMessage> state) {
        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (lastMessage instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                boolean needsApproval = aiMessage.toolExecutionRequests().stream()
                        .anyMatch(req -> toolsRequiringApproval.contains(req.name()));
                if (needsApproval) {
                    log.info("[Agent编排-条件边] 含需授权工具,路由到 review 中断点");
                    return "review";
                }
                log.info("[Agent编排-条件边] 全自主工具,直连 tools");
                return "auto";
            }
        }
        return "exit";
    }

    /**
     * Agent 对话入口(流式)
     *
     * <p>流程:
     * <ol>
     *   <li>加载会话记忆,添加 UserMessage</li>
     *   <li>构建初始状态: [SystemMessage] + 历史消息</li>
     *   <li>stream 消费 graph 执行,实时推送 chunks</li>
     *   <li>检测到中断(review 前):推送 __INTERRUPT__ 事件,挂起等待 resume</li>
     *   <li>正常完成:将最终 AiMessage 存入会话记忆</li>
     * </ol>
     *
     * @param sessionId 会话 ID,同时作为 checkpoint 的 threadId
     * @param message   用户问题
     * @return 流式输出,中断时以 __INTERRUPT__ 事件标记
     */
    public Flux<String> orchestrate(String sessionId, String message) {
        return Flux.create(sink -> {
            CompletableFuture.runAsync(() -> {
                long threadId = currentThreadId();
                STREAMING_SINKS.put(threadId, sink);
                THREAD_SESSION_IDS.put(threadId, sessionId);

                try {
                    TokenUsageTracker.begin();

                    ChatMemory memory = chatMemoryProvider.get(sessionId);
                    memory.add(UserMessage.from(message));

                    List<ChatMessage> messages = new ArrayList<>();
                    messages.add(SystemMessage.from(systemMessage));
                    messages.addAll(memory.messages());

                    RunnableConfig config = RunnableConfig.builder()
                            .threadId(sessionId)
                            .build();

                    log.info("[Agent编排] 会话[{}] 开始图编排,消息数={}", sessionId, messages.size());

                    // stream 消费,中断时迭代自然停止
                    boolean interrupted = false;
                    for (var output : compiledGraph.stream(GraphInput.args(Map.of("messages", messages)), config)) {
                        String node = output.node();
                        log.debug("[Agent编排] 会话[{}] 节点完成: {}", sessionId, node);
                        // review 节点前中断时,stream 停在 review 之前,不会输出 review 节点
                        // 这里通过检查 state 判断是否中断
                        if ("agent".equals(node)) {
                            var snapshot = compiledGraph.stateOf(config).orElse(null);
                            if (snapshot != null && "review".equals(snapshot.next())) {
                                // 检测到中断:推送待授权工具调用给前端
                                String interruptPayload = buildInterruptPayload(snapshot);
                                sink.next(INTERRUPT_EVENT + interruptPayload);
                                interrupted = true;
                                log.info("[Agent编排] 会话[{}] 中断于 review,等待人工授权", sessionId);
                                break;
                            }
                        }
                    }

                    if (!interrupted) {
                        // 正常完成:存最终 AiMessage 到会话记忆
                        var snapshot = compiledGraph.stateOf(config).orElse(null);
                        if (snapshot != null) {
                            var finalMessages = snapshot.state().messages();
                            for (int i = finalMessages.size() - 1; i >= 0; i--) {
                                if (finalMessages.get(i) instanceof AiMessage ai) {
                                    if (!ai.hasToolExecutionRequests()) {
                                        memory.add(ai);
                                        break;
                                    }
                                }
                            }
                        }
                        log.info("[Agent编排] 会话[{}] 图编排完成", sessionId);
                    }
                    sink.complete();
                } catch (Exception e) {
                    if (isCancellationException(e)) {
                        handleStop(sessionId, sink, "orchestrate");
                    } else {
                        log.error("[Agent编排] 会话[{}] 执行失败", sessionId, e);
                        sink.error(e);
                    }
                } finally {
                    TokenUsageTracker.finishAndLog();
                    cancellationTracker.clear(sessionId);
                    STREAMING_SINKS.remove(threadId);
                    THREAD_SESSION_IDS.remove(threadId);
                    PARTIAL_OUTPUTS.remove(threadId);
                }
            });
        });
    }

    /**
     * 恢复被中断的会话(人工授权后调用)
     *
     * @param sessionId 会话 ID,需与 orchestrate 时一致
     * @param approved  true=批准执行工具; false=拒绝,注入拒绝反馈让 agent 重新决策
     * @return 流式输出,resume 后 agent 回复继续推送
     */
    public Flux<String> resume(String sessionId, boolean approved) {
        return Flux.create(sink -> {
            CompletableFuture.runAsync(() -> {
                long threadId = currentThreadId();
                STREAMING_SINKS.put(threadId, sink);
                THREAD_SESSION_IDS.put(threadId, sessionId);

                try {
                    TokenUsageTracker.begin();

                    RunnableConfig config = RunnableConfig.builder()
                            .threadId(sessionId)
                            .build();

                    var snapshot = compiledGraph.stateOf(config)
                            .orElseThrow(() -> new IllegalStateException("会话[" + sessionId + "] 无检查点,无法 resume"));

                    log.info("[Agent编排] 会话[{}] resume,approved={}, 当前节点={}, next={}",
                            sessionId, approved, snapshot.node(), snapshot.next());

                    if (!approved) {
                        // 拒绝:从状态中移除待执行的工具调用 AiMessage,注入拒绝反馈 ToolExecutionResultMessage
                        // 让 agent 重新生成不带工具调用的回复
                        var messages = new ArrayList<>(snapshot.state().messages());
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                                    messages.add(ToolExecutionResultMessage.from(req,
                                            "用户拒绝了此工具调用,请直接回复或换一种方式回答"));
                                }
                                break;
                            }
                        }
                        compiledGraph.updateState(config, Map.of("messages", messages), "review");
                    }

                    // resume 继续执行
                    boolean interrupted = false;
                    for (var output : compiledGraph.stream(GraphInput.resume(), config)) {
                        String node = output.node();
                        log.debug("[Agent编排-resume] 会话[{}] 节点完成: {}", sessionId, node);
                        if ("agent".equals(node)) {
                            var currentSnapshot = compiledGraph.stateOf(config).orElse(null);
                            if (currentSnapshot != null && "review".equals(currentSnapshot.next())) {
                                String interruptPayload = buildInterruptPayload(currentSnapshot);
                                sink.next(INTERRUPT_EVENT + interruptPayload);
                                interrupted = true;
                                log.info("[Agent编排-resume] 会话[{}] 再次中断于 review", sessionId);
                                break;
                            }
                        }
                    }

                    if (!interrupted) {
                        var currentSnapshot = compiledGraph.stateOf(config).orElse(null);
                        if (currentSnapshot != null) {
                            var finalMessages = currentSnapshot.state().messages();
                            for (int i = finalMessages.size() - 1; i >= 0; i--) {
                                if (finalMessages.get(i) instanceof AiMessage ai) {
                                    if (!ai.hasToolExecutionRequests()) {
                                        ChatMemory memory = chatMemoryProvider.get(sessionId);
                                        memory.add(ai);
                                        break;
                                    }
                                }
                            }
                        }
                        log.info("[Agent编排-resume] 会话[{}] resume 完成", sessionId);
                    }
                    sink.complete();
                } catch (Exception e) {
                    if (isCancellationException(e)) {
                        handleStop(sessionId, sink, "resume");
                    } else {
                        log.error("[Agent编排-resume] 会话[{}] 执行失败", sessionId, e);
                        sink.error(e);
                    }
                } finally {
                    TokenUsageTracker.finishAndLog();
                    cancellationTracker.clear(sessionId);
                    STREAMING_SINKS.remove(threadId);
                    THREAD_SESSION_IDS.remove(threadId);
                    PARTIAL_OUTPUTS.remove(threadId);
                }
            });
        });
    }

    /**
     * 构建中断事件 payload:待授权工具调用列表(JSON 格式)
     */
    private String buildInterruptPayload(StateSnapshot<MessagesState<ChatMessage>> snapshot) {
        var messages = snapshot.state().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                StringBuilder sb = new StringBuilder("{\"tools\":[");
                var requests = ai.toolExecutionRequests();
                for (int j = 0; j < requests.size(); j++) {
                    ToolExecutionRequest req = requests.get(j);
                    if (j > 0) sb.append(",");
                    sb.append("{\"name\":\"").append(req.name()).append("\"")
                            .append(",\"arguments\":").append(req.arguments() == null ? "null" : req.arguments())
                            .append(",\"requireApproval\":").append(toolsRequiringApproval.contains(req.name()))
                            .append("}");
                }
                sb.append("]}");
                return sb.toString();
            }
        }
        return "{\"tools\":[]}";
    }

    /**
     * 判断异常链中是否包含 CancellationException
     *
     * <p>agentNode 的 future.completeExceptionally(CancellationException) 会被 future.join()
     * 包裹成 CompletionException,需递归解包判断。</p>
     */
    private boolean isCancellationException(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof CancellationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 处理用户主动停止:回滚记忆、清 checkpoint、推送停止事件
     *
     * <p>三个清理动作:
     * <ol>
     *   <li>回滚 UserMessage: orchestrate 场景下 memory.add(UserMessage) 已入库,
     *       需移除让记忆回到提问前状态;resume 场景未写记忆,跳过。</li>
     *   <li>清 checkpoint: 调 checkpointSaver.release 删除会话所有状态,不可 resume。</li>
     *   <li>推停止事件: 告知前端任务已停止。</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @param sink      SSE sink,推送停止事件
     * @param source    调用来源 "orchestrate" 或 "resume",决定是否回滚记忆
     */
    private void handleStop(String sessionId, FluxSink<String> sink, String source) {
        long threadId = currentThreadId();
        log.info("[Agent编排] 会话[{}] 任务被用户停止,source={}, 半截文本长度={}",
                sessionId, source, PARTIAL_OUTPUTS.get(threadId) == null ? 0
                        : PARTIAL_OUTPUTS.get(threadId).length());

        // 1. 回滚记忆(仅 orchestrate 场景,resume 未写记忆)
        if ("orchestrate".equals(source)) {
            try {
                ChatMemory memory = chatMemoryProvider.get(sessionId);
                if (memory instanceof DualConstraintChatMemory dual) {
                    dual.removeLastMessage();
                } else {
                    // 非 DualConstraintChatMemory 不支持 removeLastMessage,记录但不阻塞停止
                    log.warn("[Agent编排] 会话[{}] memory 非 DualConstraintChatMemory,无法回滚 UserMessage", sessionId);
                }
            } catch (Exception ex) {
                log.error("[Agent编排] 会话[{}] 回滚记忆失败", sessionId, ex);
            }
        }

        // 2. 清 checkpoint(不可恢复)
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            checkpointSaver.release(config);
            log.info("[Agent编排] 会话[{}] checkpoint 已清除", sessionId);
        } catch (Exception ex) {
            log.error("[Agent编排] 会话[{}] 清 checkpoint 失败", sessionId, ex);
        }

        // 3. 推送停止事件给前端
        sink.next(STOP_EVENT);
        sink.complete();
    }
}
