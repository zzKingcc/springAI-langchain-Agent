package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * PDF 文档处理策略 todo
 */
@Slf4j
public class PdfDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> PDF_EXTENSIONS = List.of("pdf");

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[分片写入-{}][{}] ⚠ 当前暂不支持PDF解析，已跳过加载文件[{}]。",
                    sourceTag, strategyName(), safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return PDF_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "PDF类型";
    }

    private String safeFileName(Document doc) {
        try {
            //1、文件名
            String name = doc.metadata().getString("file_name");
            if (name != null && !name.isBlank()) return name;
            //2、来源路径截取
            String src = doc.metadata().getString("source");
            if (src != null && !src.isBlank()) {
                int sep = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
                return sep >= 0 ? src.substring(sep + 1) : src;
            }
            //3、绝对路径截取
            String abs = doc.metadata().getString("absolute_path");
            if (abs != null && !abs.isBlank()) {
                int sep = Math.max(abs.lastIndexOf('/'), abs.lastIndexOf('\\'));
                return sep >= 0 ? abs.substring(sep + 1) : abs;
            }
        } catch (Exception e) {
            log.error("提取文件名时出错", e);
        }
        return "(unknown)";
    }
}
