package com.zxcSpringAI.exception;

/**
 * 会话记忆相关异常
 */
public class ChatMemoryException extends BaseException {

    public ChatMemoryException(String message) {
        super(message);
    }

    public ChatMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
