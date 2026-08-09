package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 未知/未识别文件类型处理策略
 */
@Slf4j
public class UnknownDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final UnknownDocumentProcessStrategy INSTANCE = new UnknownDocumentProcessStrategy();

    private UnknownDocumentProcessStrategy() {
    }

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[分片写入-{}][{}] ⚠ 文件[{}]的扩展名未识别，当前版本暂不支持该类型，已跳过。",
                    sourceTag, strategyName(), safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        // 兜底策略，不注册具体扩展名
        return List.of();
    }

    @Override
    public String strategyName() {
        return "未知类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
