package com.zxcSpringAI.memory;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 会话记忆配置
 */
@Configuration
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    /** 对话记忆消息条数上限 */
    private static final int MAX_MESSAGES = 100;

    /** 对话记忆 Token 估算上限 */
    private static final int MAX_TOKENS = 30000;

    /** 会话记忆 TTL：null  */
    private static final java.time.Duration SESSION_TTL = null;

    /**
     * Redis 会话记忆存储 Bean
     */
    @Bean
    public RedisChatMemoryStore redisChatMemoryStore(StringRedisTemplate stringRedisTemplate) {
        String ttlText = SESSION_TTL != null ? SESSION_TTL.toMinutes() + " 分钟" : "永久";
        log.info("[会话记忆] RedisChatMemoryStore 初始化，双约束：maxMessages={} 条（≈{} 轮问答），maxTokens={} tokens（问答累计），TTL={}",
                MAX_MESSAGES, MAX_MESSAGES / 2, MAX_TOKENS, ttlText);
        return new RedisChatMemoryStore(stringRedisTemplate);
    }

    /**
     * 多会话记忆 Provider
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

    /**
     * Graph 检查点持久化 Bean
     *
     * <p>用于 LangGraph4j 的 interrupt/resume 机制:中断时 checkpoint 落 Redis,
     * resume 时从 Redis 恢复状态。支持跨实例/重启续跑。</p>
     */
    @Bean
    public RedisCheckpointSaver redisCheckpointSaver(StringRedisTemplate stringRedisTemplate) {
        log.info("[检查点] RedisCheckpointSaver 初始化,用于 graph interrupt/resume 断点续跑");
        return new RedisCheckpointSaver(stringRedisTemplate);
    }
}
