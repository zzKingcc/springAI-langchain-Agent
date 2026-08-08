package com.zxcSpringAI.exception;

/**
 * 知识库相关异常
 *
 * <p>覆盖文档导入、分片、向量化写入、检索等环节的业务异常。</p>
 */
public class KnowledgeBaseException extends BaseException {

    public KnowledgeBaseException(String message) {
        super(message);
    }

    public KnowledgeBaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
