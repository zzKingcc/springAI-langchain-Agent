package com.zxcSpringAI.aiService;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 声明式问答服务
 *
 * 由 LangChain4j 在运行时生成代理实现，自动串联以下组件：
 * 1) chatMemoryProvider：多会话记忆，按 sessionId 隔离，Redis 持久化，保留最近 10 条消息；
 * 2) myContentRetriever：组合内容检索器（向量 Top15 + 关键词 Top5），基于用户问题从 ES 混合检索知识片段；
 * 3) openAiStreamingChatModel：流式对话模型，按分块返回生成文本。
 * 回答规则由 SystemMessage 声明，用户问题与知识片段按 UserMessage 组织后发送给模型。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "myContentRetriever"
)
public interface MyAIService {

    /**
     * 回答用户问题（流式）
     *
     * @param sessionId 会话 ID，用于多轮对话记忆隔离
     * @param message 用户问题文本
     * @return 按返回的流式文本分段结果
     */
    @SystemMessage("""
            你是喜羊羊公司的客服小妹，是一个用于回复用户问题的智能助手。请严格遵守以下回答规则：

            1. 你的回答必须完全基于"用户问题下方附带的知识库上下文"内容，绝不可以根据自身通用知识编造或补充；
            2. 如果知识库上下文为空，或者上下文内容与用户问题不相关，必须统一回复："抱歉，找不到相关内容哦，您可以换个问题试试～"，绝不允许用知识库以外的内容作答；
            3. 回答风格亲切可爱，使用简体中文，条理清晰，可以适当使用语气词（如"呢"、"哦"、"～"）；
            4. 如果用户问题与喜羊羊公司业务完全无关，请礼貌引导："我是喜羊羊公司的客服小妹，目前只能回答与公司相关的问题哦，请问有什么可以帮到您的吗～"；
            5. 回答中引用知识库内容时，可以注明来源文件名和章节（如果上下文中有附带）；
            6. 【重要】回答必须精炼简洁，单次回答不得超过 500 字，禁止冗余展开、重复说明或大段复述原文。简单问题 1~3 句内答完，复杂问题用 3~5 条要点列出，避免输出过长挤占多轮上下文窗口。
            """)
    @UserMessage("""
            用户问题：{{message}}
            （相关知识库内容将由系统自动附加在问题下方，请务必且仅基于下方内容回答）
            """)
    Flux<String> chat(@MemoryId String sessionId, @V("message") String message);

}
