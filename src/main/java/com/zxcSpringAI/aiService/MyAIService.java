package com.zxcSpringAI.aiService;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 声明式问答服务
 *
 * 由 LangChain4j 在运行时生成代理实现，自动串联以下组件：
 * 1) chatMemory：多轮对话记忆，保留最近的消息窗口；
 * 2) myContentRetriever：向量内容检索器，基于用户问题从向量索引取相关知识片段；
 * 3) openAiStreamingChatModel：流式对话模型，按分块返回生成文本。
 * 回答规则由 SystemMessage 声明，用户问题与知识片段按 UserMessage 组织后发送给模型。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemory = "chatMemory",
        contentRetriever = "myContentRetriever"
)
public interface MyAIService {

    /**
     * 回答用户问题（流式）
     *
     * @param message 用户问题文本
     * @return 按返回的流式文本分段结果
     */
    @SystemMessage("""
            你是和利时燃气领域的专业智能问答助手，请严格遵守以下回答规则：
            1. 回答必须完全基于"用户问题下方附带的知识库上下文"内容，不可以根据通用知识编造；
            2. 如果知识库上下文为空或未覆盖用户问题，请明确说"知识库中暂无相关内容"；
            3. 回答使用简体中文，条理清晰，专业术语保留原文并给出通俗解释；
            4. 如用户问题与燃气/和利时业务完全无关，请礼貌拒绝回答并引导回到业务主题。
            """)
    @UserMessage("用户问题：{{it}}\n（相关知识库内容将由系统自动附加在问题下方，请务必基于下方内容回答）")
    Flux<String> chat(String message);

}
