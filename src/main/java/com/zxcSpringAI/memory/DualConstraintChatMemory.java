package com.zxcSpringAI.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 双约束会话记忆
 * 1、消息条数，上线50伦
 * 2、Token 数，30k
 */
public class DualConstraintChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(DualConstraintChatMemory.class);

    private final Object id;
    private final int maxMessages;
    private final int maxTokens;
    private final ChatMemoryStore store;

    /**
     * @param id          会话 ID
     * @param maxMessages 消息条数上限
     * @param maxTokens   Token 估算上限
     * @param store       持久化存储（如 RedisChatMemoryStore）
     */
    public DualConstraintChatMemory(Object id, int maxMessages, int maxTokens, ChatMemoryStore store) {
        this.id = id;
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.store = store;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        messages.add(message);

        int evictedCount = 0;
        // 从最旧消息开始淘汰，直到两个约束都满足（至少保留 1 条）
        while (messages.size() > 1) {
            int currentTokens = estimateTokens(messages);
            if (messages.size() <= maxMessages && currentTokens <= maxTokens) {
                break;
            }
            messages.remove(0);
            evictedCount++;
        }

        if (evictedCount > 0) {
            log.debug("[会话记忆] 会话[{}] 淘汰 {} 条旧消息（当前 {} 条，约 {} tokens）",
                    id, evictedCount, messages.size(), estimateTokens(messages));
        }

        store.updateMessages(id, messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 移除最后一条消息(用于任务停止时回滚 UserMessage)
     * 停止时 memory.add(UserMessage) 已入库,需移除该条让记忆回到提问前状态。
     * 直接操作 store 读写,复用淘汰逻辑保证约束。
     *
     * @return true=移除成功; false=记忆为空无可移除
     */
    public boolean removeLastMessage() {
        List<ChatMessage> messages = new ArrayList<>(store.getMessages(id));
        if (messages.isEmpty()) {
            log.debug("[会话记忆] 会话[{}] removeLastMessage: 记忆为空,无可移除", id);
            return false;
        }
        ChatMessage removed = messages.remove(messages.size() - 1);
        store.updateMessages(id, messages);
        log.info("[会话记忆] 会话[{}] 移除最后一条消息,类型={}, 剩余 {} 条",
                id, removed.getClass().getSimpleName(), messages.size());
        return true;
    }


    /**
     * 估算消息列表的总 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokens(extractText(msg));
        }
        // 每条消息额外计入 4 token 的结构开销（role 标记等）
        total += messages.size() * 4;
        return total;
    }

    /**
     * 估算单段文本的 Token 数（字符级启发式）
     */
    private int estimateTokens(String text) {
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

    /**
     * 判断字符是否为 CJK 字符（中文、日文、韩文、全角符号）
     */
    private boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')   // CJK 统一表意文字
                || (c >= '\u3400' && c <= '\u4DBF') // CJK 扩展 A
                || (c >= '\u3000' && c <= '\u303F') // CJK 符号和标点
                || (c >= '\uFF00' && c <= '\uFFEF') // 全角字符
                || (c >= '\uAC00' && c <= '\uD7AF'); // 韩文音节
    }

    /**
     * 从消息中提取纯文本内容
     */
    private String extractText(ChatMessage message) {
        if (message instanceof SystemMessage sm) {
            return sm.text();
        } else if (message instanceof AiMessage am) {
            return am.text() != null ? am.text() : "";
        } else if (message instanceof UserMessage um) {
            try {
                return um.singleText();
            } catch (Exception e) {
                return um.toString();
            }
        } else if (message instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return "";
    }
}
