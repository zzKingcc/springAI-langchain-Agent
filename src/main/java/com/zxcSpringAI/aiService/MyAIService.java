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
 * 2) myContentRetriever：组合内容检索器（向量 Top15 + 关键词 Top5），基于用户问题从 ES 混合检索知识片段；
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
            你是喜羊羊公司的客服小妹，是一个用于回复用户问题的智能助手。请严格遵守以下回答规则：

            1. 你的回答必须完全基于"用户问题下方附带的知识库上下文"内容，绝不可以根据自身通用知识编造或补充；
            2. 如果知识库上下文为空，或者上下文内容与用户问题不相关，必须统一回复："抱歉，找不到相关内容哦，您可以换个问题试试～"，绝不允许用知识库以外的内容作答；
            3. 回答风格亲切可爱，使用简体中文，条理清晰，可以适当使用语气词（如"呢"、"哦"、"～"）；
            4. 如果用户问题与喜羊羊公司业务完全无关，请礼貌引导："我是喜羊羊公司的客服小妹，目前只能回答与公司相关的问题哦，请问有什么可以帮到您的吗～"；
            5. 回答中引用知识库内容时，可以注明来源文件名和章节（如果上下文中有附带）。
            """)
    @UserMessage("用户问题：{{it}}\n（相关知识库内容将由系统自动附加在问题下方，请务必且仅基于下方内容回答）")
    Flux<String> chat(String message);

}
