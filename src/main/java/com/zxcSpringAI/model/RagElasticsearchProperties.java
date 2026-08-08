package com.zxcSpringAI.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量索引配置模型
 */
@Data
@ConfigurationProperties(prefix = "rag.elasticsearch")
public class RagElasticsearchProperties {

    private String indexName = "rag_embeddings";

    /** 重建开关 */
    private boolean deleteOnStartup = true;

}