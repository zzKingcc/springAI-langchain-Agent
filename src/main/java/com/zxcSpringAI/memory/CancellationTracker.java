package com.zxcSpringAI.memory;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务取消信号中心
 *
 * <p>按 sessionId 存储取消标志,跨线程通信:停止接口(HTTP 线程)设标志,
 * 执行线程(orchestrate/agentNode/toolsNode)检查标志后抛 CancellationException。</p>
 *
 * <p>用 AtomicBoolean 而非 volatile boolean:停止接口和执行线程可能并发访问,
 * AtomicBoolean 提供原子 read-modify-write,且语义更明确。</p>
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
     * <p>执行线程在 agentNode 的流式回调、toolsNode 的工具执行前、graph 节点切换间隙调用此方法,
     * 返回 true 时抛 CancellationException 终止执行。</p>
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
     * <p>在 orchestrate/resume 的 finally 块中调用,无论正常完成还是异常终止都清除,
     * 避免标志泄漏影响下次对话。</p>
     *
     * @param sessionId 会话 ID
     */
    public void clear(String sessionId) {
        flags.remove(sessionId);
    }
}
