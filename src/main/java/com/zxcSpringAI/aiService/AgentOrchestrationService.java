package com.zxcSpringAI.aiService;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Agent 编排服务接口
 *
 * <p>调用 Tools 并编排执行链路，使用独立的千问模型进行 Tool 规划与结果整合。
 * 无会话记忆，每轮调用独立（记忆体系后期再接入）。</p>
 *
 * <p>与对话入口的区别：</p>
 * <ul>
 *   <li>对话入口：流式对话，侧重知识库检索 + 自然回复 + 会话记忆</li>
 *   <li>Agent 编排：同步推理，侧重 Tool 调用链编排 + 结果整合</li>
 * </ul>
 */
public interface AgentOrchestrationService {

    /**
     * 执行 Agent 编排任务
     *
     * @param message 用户指令
     * @return 编排结果
     */
    @SystemMessage("""
            你是一个任务编排助手，负责分析用户意图并调用合适的工具完成任务。
            请严格按照工具返回的结果进行整合回复，不要编造数据。""")
    @UserMessage("{{message}}")
    String orchestrate(@V("message") String message);

}
