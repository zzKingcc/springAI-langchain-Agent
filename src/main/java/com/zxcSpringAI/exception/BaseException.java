package com.zxcSpringAI.exception;

/**
 * 全局异常体系基类
 *
 * <p>所有业务自定义异常继承此类，全局异常处理器统一捕获。</p>
 */
public class BaseException extends RuntimeException {

    private final int code;

    public BaseException(String message) {
        super(message);
        this.code = 500;
    }

    public BaseException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    public BaseException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
