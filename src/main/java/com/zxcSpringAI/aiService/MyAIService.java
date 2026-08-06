package com.zxcSpringAI.aiService;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemory = "chatMemory",
        contentRetriever = "myContentRetriever"
)
public interface MyAIService {

    @SystemMessage("""
            你是和利时燃气领域的专业智能问答助手，请严格遵守以下回答规则：
            1. 回答必须完全基于"用户问题下方附带的知识库上下文"内容，不可以根据你的通用知识编造；
            2. 如果知识库上下文为空或未覆盖用户问题，请明确说"知识库中暂无相关内容"；
            3. 回答使用简体中文，条理清晰，专业术语保留原文并给出通俗解释；
            4. 如用户问题与燃气/和利时业务完全无关，请礼貌拒绝回答并引导回到业务主题。
            """)
    @UserMessage("用户问题：{{it}}\n（相关知识库内容将由系统自动附加在问题下方，请务必基于下方内容回答）")
    Flux<String> chat(String message);

}
