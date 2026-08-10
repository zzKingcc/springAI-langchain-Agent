package com.zxcSpringAI.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 全局异常处理器
 *
 * <p>统一拦截 Controller 层抛出的异常，返回结构化 JSON 错误响应，避免堆栈泄露。</p>
 *
 * <h3>异常处理优先级（从具体到宽泛）</h3>
 * <ol>
 *   <li>{@link KnowledgeBaseException} → 500，知识库业务异常</li>
 *   <li>{@link ChatMemoryException} → 500，会话记忆异常</li>
 *   <li>{@link BaseException} → 自定义 code，业务异常基类</li>
 *   <li>{@link IllegalArgumentException} → 400，参数校验异常</li>
 *   <li>{@link MissingPathVariableException} → 400，路径参数缺失</li>
 *   <li>{@link MethodArgumentTypeMismatchException} → 400，参数类型不匹配</li>
 *   <li>{@link SocketTimeoutException} → 504，LLM 调用超时</li>
 *   <li>{@link TimeoutException} → 504，LLM 调用超时</li>
 *   <li>{@link NoResourceFoundException} → 404，静态资源未找到（favicon.ico 等静默处理）</li>
 *   <li>{@link RuntimeException} → 500，未预期的运行时异常</li>
 *   <li>{@link Exception} → 500，兜底</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ==================== 业务异常 ====================

    @ExceptionHandler(KnowledgeBaseException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeBaseException(KnowledgeBaseException e) {
        log.error("[全局异常] 知识库异常：{}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "知识库服务异常", e.getMessage());
    }

    @ExceptionHandler(ChatMemoryException.class)
    public ResponseEntity<Map<String, Object>> handleChatMemoryException(ChatMemoryException e) {
        log.error("[全局异常] 会话记忆异常：{}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "会话记忆异常", e.getMessage());
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Map<String, Object>> handleBaseException(BaseException e) {
        log.error("[全局异常] 业务异常：code={}, message={}", e.getCode(), e.getMessage(), e);
        return buildResponse(e.getCode(), "业务异常", e.getMessage());
    }

    // ==================== 参数校验异常 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("[全局异常] 参数非法：{}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST.value(), "请求参数非法", e.getMessage());
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPathVariable(MissingPathVariableException e) {
        log.warn("[全局异常] 路径参数缺失：{}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST.value(), "路径参数缺失", e.getVariableName() + " 不能为空");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[全局异常] 参数类型不匹配：{}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST.value(), "参数类型不匹配",
                "参数 " + e.getName() + " 期望类型：" + e.getRequiredType().getSimpleName());
    }

    // ==================== LLM 超时异常 ====================

    /** LLM API 调用超时（Socket 读取超时） */
    @ExceptionHandler(SocketTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleSocketTimeoutException(SocketTimeoutException e) {
        log.error("[全局异常] LLM调用超时(Socket): {}", e.getMessage());
        return buildResponse(504, "LLM服务超时", "大模型接口响应超时，请稍后重试");
    }

    /** LLM API 调用超时（连接/请求超时） */
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeoutException(TimeoutException e) {
        log.error("[全局异常] LLM调用超时(Timeout): {}", e.getMessage());
        return buildResponse(504, "LLM服务超时", "大模型接口响应超时，请稍后重试");
    }

    // ==================== 静态资源异常 ====================

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFoundException(NoResourceFoundException e) {
        String resourcePath = e.getResourcePath();
        // 浏览器自动请求的 favicon.ico 等资源缺失属正常现象，静默返回 404，避免污染日志
        if ("favicon.ico".equals(resourcePath)) {
            return ResponseEntity.notFound().build();
        }
        log.debug("[全局异常] 静态资源未找到：{}", resourcePath);
        return buildResponse(HttpStatus.NOT_FOUND.value(), "资源未找到", "请求的资源不存在：" + resourcePath);
    }

    // ==================== 运行时异常 ====================

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("[全局异常] 未预期运行时异常：{}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误", "服务暂时不可用，请稍后重试");
    }

    // ==================== 兜底 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("[全局异常] 未预期异常：{}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误", "服务暂时不可用，请稍后重试");
    }

    // ==================== 工具方法 ====================

    /**
     * 构建统一 JSON 响应体
     */
    private ResponseEntity<Map<String, Object>> buildResponse(int code, String error, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", error);
        body.put("detail", detail);
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(code).body(body);
    }
}
