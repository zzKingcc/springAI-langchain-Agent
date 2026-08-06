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
 * 知识问答接口
 *
 * 提供基于知识库向量检索的问答能力：
 * 1) 调用内容检索器从 Elasticsearch 向量索引中查询相似片段；
 * 2) 将用户问题及检索片段交由声明式 AI 服务生成回答；
 * 3) 生成结果以流式文本响应返回。
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
     * 问答接口（流式输出）
     *
     * @param message 用户问题
     * @return 流式文本回答（按 DashScope 流式模型分块返回）
     */
    @GetMapping(value = "/message/{message}", produces = "text/html;charset=UTF-8")
    public Flux<String> getMessage(@PathVariable String message) {
        // 内容检索：先对问题进行向量相似度检索，命中结果用于后续日志排查
        try {
            List<Content> contents = myContentRetriever.retrieve(Query.from(message));
            log.info("[问答接口] 命中片段数={}，问题=\"{}\"，片段预览：{}",
                    contents.size(), message, preview(contents));
        } catch (Exception e) {
            log.error("[问答接口] 向量检索失败: {}", e.getMessage());
            printEsError(e);
            throw new RuntimeException("向量检索失败：" + e.getMessage(), e);
        }
        // 交由 AI 服务生成回答（内部会再次读取对话历史与检索片段后发送给模型）
        return myAIService.chat(message);
    }

    /**
     * 将命中片段拼接为简要预览字符串，用于日志输出
     */
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
     * 解析并输出 Elasticsearch 结构化错误信息（类型、原因、链式原因、根原因），
     * 便于根据 ES 返回内容直接判断问题来源（字段、分片、查询语法等）。
     */
    private static void printEsError(Throwable e) {
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
