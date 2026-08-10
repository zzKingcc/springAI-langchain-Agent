package com.zxcSpringAI.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 用量统计工具
 */
public final class TokenUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private static final ThreadLocal<TokenStats> STATS = ThreadLocal.withInitial(TokenStats::new);

    private TokenUsageTracker() {}

    /** 开始新一轮统计（清除上一轮残留） */
    public static void begin() {
        STATS.remove();
        STATS.set(new TokenStats());
    }

    /** 记录 LLM 输出 token（流式输出的每个 chunk） */
    public static void recordLlmOutputChunk(String chunk) {
        TokenStats s = STATS.get();
        s.llmOutputTokens += estimateTokens(chunk);
    }

    /** 记录 Tool 调用 token（输入参数 + 输出结果） */
    public static void recordToolCall(String toolName, String input, String output) {
        TokenStats s = STATS.get();
        int inputTokens = estimateTokens(input);
        int outputTokens = estimateTokens(output);
        s.toolInputTokens += inputTokens;
        s.toolOutputTokens += outputTokens;
        s.toolCallCount++;
        log.debug("[Token统计] Tool[{}] 输入≈{} tokens，输出≈{} tokens", toolName, inputTokens, outputTokens);
    }

    /** 打印统计并清除 ThreadLocal，防止内存泄漏 */
    public static void finishAndLog() {
        TokenStats s = STATS.get();
        int total = s.llmOutputTokens + s.toolInputTokens + s.toolOutputTokens;
        log.info("[Token统计] 本次请求 — LLM输出≈{} tokens | Tool调用{}次(输入≈{} + 输出≈{} tokens) | 合计≈{} tokens",
                s.llmOutputTokens, s.toolCallCount, s.toolInputTokens, s.toolOutputTokens, total);
        STATS.remove();
    }

    //1、Token 估算
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                tokens += 1.5;
            } else {
                tokens += 0.25;
            }
        }
        return (int) Math.ceil(tokens);
    }

    private static boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF00' && c <= '\uFFEF')
                || (c >= '\uAC00' && c <= '\uD7AF');
    }

    private static class TokenStats {
        int llmOutputTokens;
        int toolCallCount;
        int toolInputTokens;
        int toolOutputTokens;
    }
}