package com.zxcSpringAI.aiService;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Flux;

/**
 * 对话入口服务接口
 *
 * <p>由 {@link com.zxcSpringAI.config.ChatEntryConfig} 通过 AiServices.builder() 编程式构建，
 * 系统提示词从 application.yaml（ai.prompt.system-message）读取，修改后重启生效，无需重新编译。
 * 自动串联以下组件：</p>
 * 1) chatMemoryProvider：多会话记忆，按 sessionId 隔离，Redis 持久化；
 * 2) myContentRetriever：组合内容检索器（向量 Top15 + 关键词 Top5），基于用户问题从 ES 混合检索知识片段；
 * 3) openAiStreamingChatModel：流式对话模型，按分块返回生成文本。
 */
public interface ChatEntryService {

    @UserMessage("用户问题：{{message}}")
    Flux<String> chat(@MemoryId String sessionId, @V("message") String message);

}