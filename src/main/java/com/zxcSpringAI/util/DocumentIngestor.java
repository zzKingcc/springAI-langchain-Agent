package com.zxcSpringAI.util;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zxcSpringAI.processor.DocumentProcessStrategy;
import com.zxcSpringAI.processor.DocumentProcessStrategyFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档导入工具（策略模式调度层）
 * 1、本地导入
 * 2、todo 远程导入
 */
@Slf4j
public class DocumentIngestor {

    private DocumentIngestor() {}


    /**
     * 从本地加载知识库文档并执行向量化写入。
     *
     * @param esClient       
     * @param indexName     
     * @param embeddingStore 
     * @param embeddingModel 
     * @return 
     */
    public static int ingestKnowledgeBase(ElasticsearchClient esClient,
                                          String indexName,
                                          EmbeddingStore embeddingStore,
                                          EmbeddingModel embeddingModel) {
        log.info("[知识库导入-本地] 开始从 classpath:ragDatabase 加载文档...");
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("ragDatabase");
        int docCount = documents == null ? 0 : documents.size();
        log.info("[知识库导入-本地] 扫描到知识库文档数: {} 个文档", docCount);

        if (docCount == 0) {
            return 0;
        }

        return doIngest(documents, esClient, indexName, embeddingStore, embeddingModel, "本地");
    }




    /**
     * 策略分发
     *
     * @param documents      原始文档列表（可能混合多种文件类型）
     * @param esClient       
     * @param indexName      
     * @param embeddingStore 
     * @param embeddingModel 
     * @param sourceTag      来源标签（"本地"/"外部"）
     * @return 
     */
    private static int doIngest(List<Document> documents,
                                ElasticsearchClient esClient,
                                String indexName,
                                EmbeddingStore embeddingStore,
                                EmbeddingModel embeddingModel,
                                String sourceTag) {
        int docCount = documents.size();

        // 1、按扩展名分组到对应策略
        Map<DocumentProcessStrategy, List<Document>> group =
                DocumentProcessStrategyFactory.groupByStrategy(documents);

        // 2、打印分组统计
        StringBuilder summary = new StringBuilder();
        summary.append("[知识库导入-").append(sourceTag).append("] 按文件类型分组：")
                .append("共 ").append(docCount).append(" 个文档 → ");
        int idx = 0;
        for (Map.Entry<DocumentProcessStrategy, List<Document>> e : group.entrySet()) {
            if (idx++ > 0) summary.append(", ");
            summary.append(e.getKey().strategyName())
                    .append('=').append(e.getValue().size());
        }
        log.info(summary.toString());

        // 3、依次调用每个策略
        int totalProcessed = 0;
        for (Map.Entry<DocumentProcessStrategy, List<Document>> e : group.entrySet()) {
            DocumentProcessStrategy strategy = e.getKey();
            List<Document> docsOfStrategy = e.getValue();
            try {
                int processed = strategy.process(
                        docsOfStrategy, esClient, indexName, embeddingStore, embeddingModel, sourceTag);
                totalProcessed += processed;
                log.debug("[知识库导入-{}] 策略[{}]处理完成：返回 {} 个文档",
                        sourceTag, strategy.strategyName(), processed);
            } catch (Exception ex) {
                // 单策略异常不影响其他策略继续执行
                log.error("[知识库导入-{}] 策略[{}]处理异常，已跳过该组：{}",
                        sourceTag, strategy.strategyName(), ex.getMessage(), ex);
            }
        }

        log.info("[知识库导入-{}] 全部分组处理结束，总处理文档数：{}", sourceTag, totalProcessed);
        return totalProcessed;
    }


    /**
     * TODO: 外部文档导入的具体实现
     *
     * @param documents
     * @param esClient
     * @param indexName
     * @param embeddingStore
     * @param embeddingModel
     * @return
     */
    public static int ingestExternalDocuments(List<Document> documents,
                                              ElasticsearchClient esClient,
                                              String indexName,
                                              EmbeddingStore embeddingStore,
                                              EmbeddingModel embeddingModel) {
        log.warn("[知识库导入-外部] 外部文档导入功能尚未实现，当前调用将被忽略。");
        return 0;
    }

}
