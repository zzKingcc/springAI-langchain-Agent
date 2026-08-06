package com.zxcSpringAI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 向量索引配置
 *
 * 对应 application.yaml 中 rag.elasticsearch.* 配置项，
 * 用于指定向量索引名及启动时的索引清理策略。
 */
@Data
@ConfigurationProperties(prefix = "rag.elasticsearch")
public class RagElasticsearchProperties {

    /** 向量索引名称：LangChain4j 向量化文档后写入该索引，检索查询亦针对此索引 */
    private String indexName = "rag_embeddings";

    /** 启动时是否删除旧索引并重建；索引结构变更或知识库重置时使用 */
    private boolean deleteOnStartup = false;

}
