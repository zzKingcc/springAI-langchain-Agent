package com.zxcSpringAI.memory;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务取消信号中心
 */
@Component
public class CancellationTracker {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /**
     * 请求停止指定会话的任务
     *
     * @param sessionId 会话 ID
     * @return true=本次设置成功(之前未停止); false=该会话已处于停止状态(幂等)
     */
    public boolean requestStop(String sessionId) {
        return flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false))
                .compareAndSet(false, true);
    }

    /**
     * 检查会话是否已被请求停止
     *
     * @param sessionId 会话 ID
     * @return true=已被请求停止
     */
    public boolean isCancelled(String sessionId) {
        AtomicBoolean flag = flags.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * 清除会话的停止标志
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        flags.remove(sessionId);
    }
}
