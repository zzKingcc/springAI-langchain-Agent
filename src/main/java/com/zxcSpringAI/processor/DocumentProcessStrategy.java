package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 文档处理策略接口
 */
public interface DocumentProcessStrategy {

    /**
     * 流程：解析 → 分片 → 去重 → 向量化写入。
     *
     * @param documents      待处理的文档列表（已按文件类型分组，全部匹配本策略）
     * @param esClient       
     * @param indexName      
     * @param embeddingStore 
     * @param embeddingModel 
     * @param sourceTag      来源标签（"本地"/"外部"）
     * @return 
     */
    int process(List<Document> documents,
                ElasticsearchClient esClient,
                String indexName,
                EmbeddingStore embeddingStore,
                EmbeddingModel embeddingModel,
                String sourceTag);

    /**
     * @return 本策略支持的文件扩展名集合（小写，不含点），用于工厂匹配
     */
    List<String> supportedExtensions();

    /**
     * @return 策略名称
     */
    String strategyName();
}
