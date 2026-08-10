package com.zxcSpringAI.aiService;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 统一 Agent 对话服务接口
 *
 * <p>融合 RAG 知识库检索 + Tool 工具调用 + 会话记忆，作为唯一的用户对话入口。
 * 流式输出，模型自主编排检索与工具调用顺序，最终整合回答逐块返回。</p>
 *
 * <p>能力清单：</p>
 * <ul>
 *   <li>混合检索：向量 Top15 + 关键词 Top5 + 分数融合重排序 Top10（通过 searchKnowledgeBase 工具）</li>
 *   <li>工具调用：天气查询等业务工具（通过 @Tool 注解声明）</li>
 *   <li>会话记忆：按 sessionId 隔离，Redis 持久化，双约束窗口（100 条 / 30K tokens）</li>
 *   <li>流式输出：Tool 调用完成后最终整合回答逐块流式返回</li>
 * </ul>
 */
public interface AgentOrchestrationService {

    @UserMessage("用户问题：{{message}}")
    TokenStream orchestrate(@MemoryId String sessionId, @V("message") String message);

}