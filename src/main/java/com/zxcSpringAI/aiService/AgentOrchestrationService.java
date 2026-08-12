package com.zxcSpringAI.aiService;

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
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.prebuilt.MessagesStateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 基于 LangGraph4j 的 Agent 编排服务
 * START → agent → (条件边: 有工具调用?) → tools → agent (循环) → END
 */
@Slf4j
public class AgentOrchestrationService {
    //编译后的 LangGraph4j 状态图，定义 agent→tools→agent 的循环流转逻辑
    private final CompiledGraph<MessagesState<ChatMessage>> compiledGraph;
    //会话记忆
    private final ChatMemoryProvider chatMemoryProvider;
    //流式对话模型
    private final StreamingChatModel streamingChatModel;
    //工具规格列表，描述每个工具的名称、参数、描述等元信息
    private final List<ToolSpecification> toolSpecs;
    //工具执行器映射，将工具名称映射到对应的工具执行器
    private final Map<String, ToolExecutor> toolExecutors;
    private final String systemMessage;

    /** 按线程 ID 存储流式 sink，供 agent 节点推送 chunks */
    @SuppressWarnings("deprecation")
    private static final ConcurrentHashMap<Long, FluxSink<String>> STREAMING_SINKS = new ConcurrentHashMap<>();

    private static long currentThreadId() {
        return Thread.currentThread().getId();
    }

    public AgentOrchestrationService(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            Tools tools,
            String systemMessage) {

        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.systemMessage = systemMessage;

        // 从 Tools 对象提取 @Tool 规格和执行器
        this.toolSpecs = new ArrayList<>();
        this.toolExecutors = new HashMap<>();
        for (Method m : tools.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                //获取@Tool 方法的注解，提取工具名称、参数、描述等信息
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                toolSpecs.add(spec);
                toolExecutors.put(spec.name(), new DefaultToolExecutor(tools, m));
            }
        }

        this.compiledGraph = buildGraph();

        log.info("[Agent编排-LangGraph] 初始化完成，已注册 {} 个工具，图结构: agent→tools→agent(条件循环)",
                toolSpecs.size());
    }

    /**
     * 构建 StateGraph 并编译
     * 有向图环路 ：START → "agent" → addConditionalEdges → "tools" → "agent" (循环)
     *                                    └→ END
     */
    private CompiledGraph<MessagesState<ChatMessage>> buildGraph() {
        try {
            //声明状态序列化器，用于在节点之间传递状态
            var stateSerializer = new ObjectStreamStateSerializer<MessagesState<ChatMessage>>(MessagesState::new);

            //声明状态图，定义节点和边的结构
            var workflow = new MessagesStateGraph<ChatMessage>(stateSerializer)
                    .addNode("agent", node_async(this::agentNode)) // 调用流式模型，推送 chunks 到 FluxSink，返回 AiMessage 到状态
                    .addNode("tools", node_async(this::toolsNode)) // 调用工具执行器，根据工具调用结果更新状态
                    .addEdge(START, "agent") // 从 START 节点到 agent 节点
                    .addConditionalEdges("agent", edge_async(this::routeAfterAgent),
                            Map.of("next", "tools", "exit", END)) // 条件边：根据是否调用工具，选择 next 或 exit 边
                    .addEdge("tools", "agent"); // 从 tools 节点到 agent 节点

            return workflow.compile();
        } catch (org.bsc.langgraph4j.GraphStateException e) {
            throw new IllegalStateException("StateGraph 构建失败", e);
        }
    }

    /**
     * agent 节点：调用流式模型，推送 chunks 到 FluxSink，返回 AiMessage 到状态
     * 节点阻塞等待流式输出完成，期间 chunks 实时推送到客户端。
     * 工具规格随请求一并发送，由 LLM 自主决定是否调用。
     */
    private Map<String, Object> agentNode(MessagesState<ChatMessage> state) {
        FluxSink<String> sink = STREAMING_SINKS.get(currentThreadId());

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
                if (sink != null) {
                    TokenUsageTracker.recordLlmOutputChunk(partialResponse);
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
        return Map.of("messages", aiMessage);
    }

    /**
     * tools 节点：执行 LLM 请求的工具调用，返回 ToolExecutionResultMessage 列表到状态
     */
    private Map<String, Object> toolsNode(MessagesState<ChatMessage> state) {
        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (!(lastMessage instanceof AiMessage aiMessage)) {
            throw new IllegalStateException("最后一条消息不是 AiMessage");
        }

        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
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
     * 条件边：agent 节点执行后，根据 AiMessage 是否含工具调用请求决定路由
     * @return "next" → tools 节点; "exit" → END
     */
    private String routeAfterAgent(MessagesState<ChatMessage> state) {
        var lastMessage = state.lastMessage()
                .orElseThrow(() -> new IllegalStateException("消息列表为空"));

        if (lastMessage instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                return "next";
            }
        }
        return "exit";
    }

    /**
     * Agent 对话入口（流式）
     * 流程：
     *   1、加载会话记忆，添加 UserMessage
     *   2、构建初始状态: [SystemMessage] + 历史消息
     *   3、执行 StateGraph（agent→tools 循环）
     *   4、流式 chunks 实时推送到 Flux
     *   5、完成后将最终 AiMessage 存入会话记忆
     */
    public Flux<String> orchestrate(String sessionId, String message) {
        return Flux.create(sink -> {
            CompletableFuture.runAsync(() -> {
                long threadId = currentThreadId();
                STREAMING_SINKS.put(threadId, sink);

                try {
                    TokenUsageTracker.begin();

                    // 1. 加载会话记忆并添加用户消息
                    ChatMemory memory = chatMemoryProvider.get(sessionId);
                    memory.add(UserMessage.from(message));

                    // 2. 构建初始消息列表: 系统提示词 + 历史对话
                    List<ChatMessage> messages = new ArrayList<>();
                    messages.add(SystemMessage.from(systemMessage));
                    messages.addAll(memory.messages());

                    // 3. 执行图编排
                    log.info("[Agent编排] 会话[{}] 开始图编排，消息数={}", sessionId, messages.size());
                    var result = compiledGraph.invoke(Map.of("messages", messages));
                    var finalState = result.get();

                    // 4. 将最终 AI 回复存入会话记忆（不含工具调用的 AiMessage）
                    var finalMessages = finalState.messages();
                    for (int i = finalMessages.size() - 1; i >= 0; i--) {
                        if (finalMessages.get(i) instanceof AiMessage ai) {
                            if (!ai.hasToolExecutionRequests()) {
                                memory.add(ai);
                                break;
                            }
                        }
                    }

                    log.info("[Agent编排] 会话[{}] 图编排完成", sessionId);
                    sink.complete();
                } catch (Exception e) {
                    log.error("[Agent编排] 会话[{}] 执行失败", sessionId, e);
                    sink.error(e);
                } finally {
                    TokenUsageTracker.finishAndLog();
                    STREAMING_SINKS.remove(threadId);
                }
            });
        });
    }
}
