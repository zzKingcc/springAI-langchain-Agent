package com.zxcSpringAI.memory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 会话记忆配置
 *
 * <p>构建基于 Redis 持久化的多会话记忆体系：</p>
 *
 * <pre>
 *   用户请求（携带 sessionId）
 *       │
 *       ▼
 *   ChatMemoryProvider（按 sessionId 创建/获取 ChatMemory）
 *       │
 *       ▼
 *   DualConstraintChatMemory（双约束：≤50 条 且 ≤30K tokens）
 *       │
 *       ▼
 *   RedisChatMemoryStore（持久化到 Redis，TTL=30 分钟）
 *       │
 *       ▼
 *   Redis: chat:memory:{sessionId} → Hash<index, ChatMessage JSON>
 * </pre>
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>多会话隔离：每个 sessionId 独立 Redis Key，互不干扰</li>
 *   <li>双约束窗口：消息数 ≤ 50 且 Token 估算 ≤ 30K，满足任一即淘汰最旧消息</li>
 *   <li>持久化：Redis 存储，重启不丢失</li>
 *   <li>自动过期：30 分钟无活动的会话自动清除，避免无限膨胀</li>
 * </ul>
 */
@Configuration
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    /** 对话记忆消息条数上限（1问+1答=2条，100条 ≈ 最大 50 轮完整问答） */
    private static final int MAX_MESSAGES = 100;

    /** 对话记忆 Token 估算上限（问+答累计，喂给 AI 的整个上下文总长度） */
    private static final int MAX_TOKENS = 30000;

    /** 会话记忆 TTL：null 表示永久不过期（后期根据业务需要再改为有限时长，如 Duration.ofMinutes(30)） */
    private static final java.time.Duration SESSION_TTL = null;

    /**
     * Redis 会话记忆存储 Bean
     */
    @Bean
    public RedisChatMemoryStore redisChatMemoryStore(RedisTemplate<String, Object> redisTemplate) {
        String ttlText = SESSION_TTL != null ? SESSION_TTL.toMinutes() + " 分钟" : "永久";
        log.info("[会话记忆] RedisChatMemoryStore 初始化，双约束：maxMessages={} 条（≈{} 轮问答），maxTokens={} tokens（问答累计），TTL={}",
                MAX_MESSAGES, MAX_MESSAGES / 2, MAX_TOKENS, ttlText);
        return new RedisChatMemoryStore(redisTemplate);
    }

    /**
     * 多会话记忆 Provider
     *
     * <p>LangChain4j 的 {@link ChatMemoryProvider} 按每个 sessionId 创建独立的
     * {@link DualConstraintChatMemory}，底层共享同一个 {@link RedisChatMemoryStore}。</p>
     *
     * <p>配合 {@code @AiService} 的 {@code chatMemoryProvider} 属性使用，
     * Controller 层通过 {@code @MemoryId} 传入 sessionId 即可实现多会话隔离。</p>
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisChatMemoryStore) {
        return memoryId -> new DualConstraintChatMemory(
                memoryId,
                MAX_MESSAGES,
                MAX_TOKENS,
                redisChatMemoryStore
        );
    }
}
