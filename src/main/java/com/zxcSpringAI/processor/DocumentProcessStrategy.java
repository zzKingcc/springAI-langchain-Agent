package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 文档处理策略接口（策略模式）
 *
 * <p>不同文件类型对应不同的处理流程，后续扩展 .pdf/.docx 等新类型时，
 * 只需新增实现类并在 {@link DocumentProcessStrategyFactory} 注册映射即可。</p>
 */
public interface DocumentProcessStrategy {

    /**
     * 处理一批文档：解析 → 分片 → 去重 → 向量化写入。
     *
     * @param documents      待处理的文档列表（已按文件类型分组，全部匹配本策略）
     * @param esClient       ES 客户端（用于去重查询）
     * @param indexName      ES 索引名
     * @param embeddingStore 向量存储实例
     * @param embeddingModel 文本嵌入模型
     * @param sourceTag      来源标签（"本地"/"外部"），仅用于日志区分
     * @return 本次处理成功并实际写入的原始文档数量（非 chunk 数）
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
     * @return 策略名称（用于日志输出，如 "文本类型"、"PDF类型"、"Office文档类型"）
     */
    String strategyName();
}
