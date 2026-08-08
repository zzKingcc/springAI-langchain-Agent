package com.zxcSpringAI.memory;

import com.zxcSpringAI.exception.ChatMemoryException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的会话记忆持久化存储
 *
 * <p>实现 LangChain4j {@link ChatMemoryStore} 接口，将多轮对话消息持久化到 Redis，
 * 支持多会话隔离、自动过期、重启不丢失。</p>
 *
 * <h3>Redis Key 设计</h3>
 * <pre>
 *   chat:memory:{sessionId}
 *     ├── 类型：Hash
 *     ├── Field：消息索引（0, 1, 2...）
 *     ├── Value：ChatMessage 的 JSON 序列化
 *     └── TTL：永久（后期根据业务需要改为有限时长，如 30 分钟）
 * </pre>
 *
 * <h3>多会话隔离</h3>
 * <p>每个 sessionId 对应独立的 Hash Key，不同用户/会话互不干扰。
 * 配合 {@code @MemoryId} 注解实现按会话路由。</p>
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "chat:memory:";

    /** 会话记忆过期时间：null 表示永久不过期（后期根据业务需要再改为有限时长） */
    private static final Duration TTL = null;

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisChatMemoryStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            // HGETALL 同时拿 field（索引）和 value（消息），保证可以按 field 恢复写入顺序
            Map<Object, Object> rawMap = redisTemplate.opsForHash().entries(key);

            if (rawMap == null || rawMap.isEmpty()) {
                log.debug("[会话记忆] 读取会话[{}]：无历史消息", memoryId);
                return new ArrayList<>();
            }

            // 先收集成 List<{索引, 消息}> 对
            List<Map.Entry<Object, Object>> entries = new ArrayList<>(rawMap.entrySet());

            // 核心：按 field（数字索引字符串）升序排序，保证上下文问答顺序正确
            // 无法解析为数字的 field 放到最后并告警
            entries.sort(Comparator.comparingInt(entry -> {
                try {
                    return Integer.parseInt(entry.getKey().toString());
                } catch (NumberFormatException e) {
                    log.warn("[会话记忆] 会话[{}]发现无法解析的消息索引字段：{}，放到末尾", memoryId, entry.getKey());
                    return Integer.MAX_VALUE;
                }
            }));

            // 按排序后的顺序组装消息列表
            List<ChatMessage> messages = new ArrayList<>(entries.size());
            for (Map.Entry<Object, Object> entry : entries) {
                Object raw = entry.getValue();
                if (raw instanceof ChatMessage msg) {
                    messages.add(msg);
                } else if (raw != null) {
                    log.warn("[会话记忆] 会话[{}]索引[{}]存在无法反序列化的消息类型：{}",
                            memoryId, entry.getKey(), raw.getClass());
                }
            }

            log.debug("[会话记忆] 读取会话[{}]：{} 条历史消息（已按索引排序）", memoryId, messages.size());
            return messages;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话记忆] 读取会话[{}]失败：{}", memoryId, e.getMessage(), e);
            throw new ChatMemoryException("读取会话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            // 先清除旧消息，再写入新消息
            redisTemplate.delete(key);

            if (messages == null || messages.isEmpty()) {
                log.debug("[会话记忆] 会话[{}]消息列表为空，已清除旧记录", memoryId);
                return;
            }

            // 逐条写入 Hash，field 为索引
            for (int i = 0; i < messages.size(); i++) {
                redisTemplate.opsForHash().put(key, String.valueOf(i), messages.get(i));
            }

            // 刷新 TTL（TTL 为 null 表示永久不过期，跳过 expire）
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
}
