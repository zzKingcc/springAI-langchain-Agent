package com.zxcSpringAI.controller;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxcSpringAI.aiService.MyAIService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 测试接口
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MyAIService myAIService;

    @Autowired
    private ContentRetriever myContentRetriever;

    /**
     * RAG 问答接口（流式输出）
     * 流程：向量相似度检索 -> AI 结合上下文生成回答 -> Flux 流式返回
     */
    @GetMapping(value = "/message/{message}", produces = "text/html;charset=UTF-8")
    public Flux<String> getMessage(@PathVariable String message) {
        // 1. 先执行向量检索（用于日志排查，AI Service 内部会再检索一次）
        try {
            List<Content> contents = myContentRetriever.retrieve(Query.from(message));
            log.info("[RAG] 命中片段数={}，问题=\"{}\"，片段预览：{}",
                    contents.size(), message, preview(contents));
        } catch (Exception e) {
            log.error("[RAG] 向量检索失败: {}", e.getMessage());
            printEsRootCause(e);
            throw new RuntimeException("向量检索失败：" + e.getMessage(), e);
        }
        // 2. 交给 LangChain4j AI Service（自动装配 chatMemory + 检索器 + 流式对话模型）
        return myAIService.chat(message);
    }

    // ======================================================================
    // 辅助方法
    // ======================================================================

    private static String preview(List<Content> contents) {
        if (contents == null || contents.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            String t = contents.get(i).textSegment().text();
            if (t.length() > 120) t = t.substring(0, 120) + "...";
            sb.append("\n  [").append(i).append("] ").append(t);
        }
        return sb.toString();
    }

    /**
     * 解析并打印 ES 的结构化错误（root_cause / caused_by / type / reason）
     * 避免只看到 "all shards failed" 而定位不到真实问题
     */
    private static void printEsRootCause(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof ElasticsearchException esEx) {
                try {
                    ErrorCause err = esEx.response().error();
                    log.error("============= ES 结构化错误 =============");
                    log.error("type        : {}", err.type());
                    log.error("reason      : {}", err.reason());
                    log.error("caused_by   : {}", err.causedBy() != null
                            ? "type=" + err.causedBy().type() + " | reason=" + err.causedBy().reason()
                            : "(none)");
                    if (err.rootCause() != null && !err.rootCause().isEmpty()) {
                        log.error("root_cause  : {}", OBJECT_MAPPER.writeValueAsString(err.rootCause()));
                    }
                    log.error("==========================================");
                } catch (Exception ex) {
                    log.error("解析 ES 错误响应失败: {}", ex.getMessage());
                }
                return;
            }
            c = c.getCause();
        }
    }

}
