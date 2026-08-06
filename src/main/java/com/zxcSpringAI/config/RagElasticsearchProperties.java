package com.zxcSpringAI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application.yaml 中 rag.elasticsearch.* 配置
 */
@Data
@ConfigurationProperties(prefix = "rag.elasticsearch")
public class RagElasticsearchProperties {

    /** 向量索引名称 */
    private String indexName = "rag_embeddings";

    /** 启动时是否删除现有索引重建（首次部署或索引结构变更时设为 true） */
    private boolean deleteOnStartup = false;

}
