package com.zxcSpringAI.memory;

import com.zxcSpringAI.exception.ChatMemoryException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的会话记忆持久化存储
 *
 * <p>实现 LangChain4j {@link ChatMemoryStore} 接口，将多轮对话消息持久化到 Redis，
 * 支持多会话隔离、自动过期、重启不丢失。</p>
 *
 * <h3>Redis Key 设计</h3>
 * <pre>
 *   chat:memory:{sessionId}
 *     ├── 类型：String
 *     ├── Value：ChatMessageSerializer.messagesToJson() 序列化的 JSON 字符串
 *     └── TTL：永久（后期根据业务需要改为有限时长，如 30 分钟）
 * </pre>
 *
 * <h3>序列化方式</h3>
 * <p>使用 langchain4j 原生的 {@link ChatMessageSerializer} / {@link ChatMessageDeserializer}
 * 进行消息序列化，避免 Jackson 对 langchain4j 消息类（如 SystemMessage）反序列化时的构造器缺失问题。</p>
 *
 * <h3>多会话隔离</h3>
 * <p>每个 sessionId 对应独立的 Redis Key，不同用户/会话互不干扰。
 * 配合 {@code @MemoryId} 注解实现按会话路由。</p>
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "chat:memory:";

    /** 会话记忆过期时间：null 表示永久不过期（后期根据业务需要再改为有限时长） */
    private static final Duration TTL = null;

    private final StringRedisTemplate redisTemplate;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            String json = redisTemplate.opsForValue().get(key);

            if (json == null || json.isBlank()) {
                log.debug("[会话记忆] 读取会话[{}]：无历史消息", memoryId);
                return new ArrayList<>();
            }

            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            log.debug("[会话记忆] 读取会话[{}]：{} 条历史消息", memoryId, messages.size());
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            // 旧数据兼容：之前用 Hash + Jackson 序列化存储，切换为 String + langchain4j 序列化后，
            // 读取旧 Hash key 会触发 WRONGTYPE（嵌套在 cause 链中）。此时删除旧 key，返回空列表。
            if (containsWrongType(e)) {
                log.warn("[会话记忆] 会话[{}]存在旧格式数据（Hash类型），已自动清除并重置", memoryId);
                redisTemplate.delete(key);
                return new ArrayList<>();
            }
            log.error("[会话记忆] 读取会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException("读取会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            if (messages == null || messages.isEmpty()) {
                redisTemplate.delete(key);
                log.debug("[会话记忆] 会话[{}]消息列表为空，已清除旧记录", memoryId);
                return;
            }

            String json = ChatMessageSerializer.messagesToJson(messages);
            redisTemplate.opsForValue().set(key, json);

            if (TTL != null) {
                redisTemplate.expire(key, TTL);
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL={} 分钟",
                        memoryId, messages.size(), TTL.toMinutes());
            } else {
                log.debug("[会话记忆] 更新会话[{}]：写入 {} 条消息，TTL=永久",
                        memoryId, messages.size());
            }
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 更新会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException("更新会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            redisTemplate.delete(key);
            log.debug("[会话记忆] 删除会话[{}]记忆", memoryId);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 删除会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException("删除会话记忆失败: " + e.getMessage(), e);
        }
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

    /**
     * 遍历异常 cause 链，检查是否包含 WRONGTYPE（Redis key 类型不匹配）。
     * 顶层异常消息通常只有 "Error in execution"，真正的 WRONGTYPE 在嵌套 cause 中。
     */
    private boolean containsWrongType(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("WRONGTYPE")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
