package com.zxcSpringAI.exception;

/**
 * 知识库相关异常
 */
public class KnowledgeBaseException extends BaseException {

    public KnowledgeBaseException(String message) {
        super(message);
    }

    public KnowledgeBaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
