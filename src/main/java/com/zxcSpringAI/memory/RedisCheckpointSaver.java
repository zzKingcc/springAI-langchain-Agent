package com.zxcSpringAI.memory;

import com.zxcSpringAI.exception.ChatMemoryException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.CheckpointListSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.*;
import java.util.*;

/**
 * 基于 Redis 的 graph 检查点持久化
 */
public class RedisCheckpointSaver implements BaseCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointSaver.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "graph:checkpoint:";

    private final StringRedisTemplate redisTemplate;
    private final CheckpointListSerializer checkpointListSerializer;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, StateSerializer<?> stateSerializer) {
        this.redisTemplate = redisTemplate;
        this.checkpointListSerializer = new CheckpointListSerializer(stateSerializer);
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            // 反转:最新在前,符合 BaseCheckpointSaver 的约定(getStateHistory 取第一个为最新)
            Collections.reverse(all);
            return all;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] list 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException("读取检查点列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            if (all.isEmpty()) {
                log.debug("[检查点] get 会话[{}]: 无检查点", threadId(config));
                return Optional.empty();
            }
            // 优先按 checkPointId 精确匹配
            String cpId = config.checkPointId().orElse(null);
            if (cpId != null) {
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (cpId.equals(all.get(i).getId())) {
                        return Optional.of(all.get(i));
                    }
                }
            }
            // 否则取最新
            Checkpoint latest = all.get(all.size() - 1);
            log.debug("[检查点] get 会话[{}]: 命中 checkpoint={}", threadId(config), latest.getId());
            return Optional.of(latest);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] get 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException("读取检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            all.add(checkpoint);
            writeAll(key, all);
            log.info("[检查点] put 会话[{}]: 写入 checkpoint={}, node={}, next={}, 累计={}",
                    threadId(config), checkpoint.getId(), checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(), all.size());
            // 返回带新 checkPointId 的 config,resume 时据此定位
            return RunnableConfig.builder(config)
                    .checkPointId(checkpoint.getId())
                    .nextNode(checkpoint.getNextNodeId())
                    .build();
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] put 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException("写入检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            Tag tag = new Tag(threadId(config), all);
            redisTemplate.delete(key);
            log.info("[检查点] release 会话[{}]: 释放 {} 个检查点", threadId(config), all.size());
            return tag;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] release 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException("释放检查点失败: " + e.getMessage(), e);
        }
    }

    private String buildKey(RunnableConfig config) {
        return KEY_PREFIX + threadId(config);
    }

    /**
     * 读取 threadId 下全部 checkpoint,保持写入顺序(旧→新)
     */
    private LinkedList<Checkpoint> readAll(String key) {
        String base64 = redisTemplate.opsForValue().get(key);
        if (base64 == null || base64.isBlank()) {
            return new LinkedList<>();
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return checkpointListSerializer.read(ois);
        } catch (Exception e) {
            log.warn("[检查点] 反序列化失败,当作空列表处理: {}", e.getMessage());
            return new LinkedList<>();
        }
    }

    /**
     * 写入 threadId 下全部 checkpoint
     */
    private void writeAll(String key, LinkedList<Checkpoint> all) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            checkpointListSerializer.write(all, oos);
        }
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        redisTemplate.opsForValue().set(key, base64);
    }
}
