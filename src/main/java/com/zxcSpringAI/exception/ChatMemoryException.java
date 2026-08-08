package com.zxcSpringAI.exception;

/**
 * 会话记忆相关异常
 *
 * <p>覆盖 Redis 读写失败、会话不存在、记忆窗口溢出等场景。</p>
 */
public class ChatMemoryException extends BaseException {

    public ChatMemoryException(String message) {
        super(message);
    }

    public ChatMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
