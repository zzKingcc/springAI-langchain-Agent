package com.zxcSpringAI.util;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识库文档导入工具
 *
 * 负责从 classpath:ragDatabase 目录加载知识库文档，
 * 并通过 EmbeddingModel 向量化后写入 Elasticsearch 向量存储。
 *
 * todo：分片逻辑
 */
public class DocumentIngestor {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestor.class);

    private DocumentIngestor() {
    }

    /**
     * 加载知识库文档并执行向量化写入
     *
     * @param embeddingStore 向量存储实例
     * @param embeddingModel 文本嵌入模型
     * @return 实际导入的文档数量（原始文档数，非切分后段数）
     */
    public static int ingestKnowledgeBase(EmbeddingStore embeddingStore, EmbeddingModel embeddingModel) {
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("ragDatabase");
        int docCount = documents == null ? 0 : documents.size();
        log.warn("[知识库导入] 加载到知识库文档数(raw): {}", docCount);

        if (docCount > 0) {
            String sample = documents.get(0).text();
            log.warn("[知识库导入] 文档[0]预览(前200字): {}",
                    sample.length() > 200 ? sample.substring(0, 200) + "..." : sample);
            try {
                int dim = embeddingModel.embed("维度自测").content().vector().length;
                log.warn("[知识库导入] EmbeddingModel 输出向量维度: {}", dim);
            } catch (Exception ignored) {
            }
            log.warn("[知识库导入] 开始向量化写入...");
            EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .build()
                    .ingest(documents);
            log.warn("[知识库导入] ingest 调用完成。");
        }
        return docCount;
    }
}
