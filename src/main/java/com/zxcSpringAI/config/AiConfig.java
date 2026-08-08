package com.zxcSpringAI.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zxcSpringAI.model.RagElasticsearchProperties;
import com.zxcSpringAI.retriever.NativeScriptScoreContentRetriever;
import com.zxcSpringAI.util.DocumentIngestor;
import com.zxcSpringAI.util.VectorStoreUtil;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationScript;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识问答核心装配
 *
 * 作为 AI 能力的 Spring 配置入口，集中声明以下 Bean：
 * 1) 对话记忆：保留最近消息窗口，用于多轮问答上下文；
 * 2) 向量存储：基于 ElasticsearchEmbeddingStore，装配前执行启动诊断与索引清理，
 *    装配后执行知识库文档导入与写入后校验；
 * 3) 内容检索器：基于高级客户端 script_score 查询实现，从向量索引检索相似片段。
 * ES 客户端（RestClient 作为基底、ElasticsearchClient 作为封装 API）由独立的
 * ElasticsearchConfig 负责装配。
 */
@Configuration
@EnableConfigurationProperties(RagElasticsearchProperties.class)
public class AiConfig {

    /**
     * 对话记忆：保留最近 10 条消息，用于多轮问答上下文维持
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();
    }

    /**
     * 余弦相似度检索器：将用户问题转成向量后，在 ES 中以余弦相似度检索知识片段。（构建 script_score 查询）
     */
    @Bean
    public ContentRetriever myContentRetriever(
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return new NativeScriptScoreContentRetriever(
                esClient,
                ragEsProps.getIndexName(),
                embeddingModel,
                15,
                0.2
        );
    }

    /**
     * 向量存储：
     * 1) 启动时执行 ES 环境诊断（版本、集群健康、索引状态）；
     * 2) 根据 delete-on-startup 配置清理旧索引；
     * 3) 构建基于 script_score 的 ElasticsearchEmbeddingStore；
     * 4) 从 classpath:ragDatabase 加载知识库文档并执行向量化写入；
     * 5) 写入完成后执行索引校验（文档数、mapping、script_score 查询示例）。
     * 全部调用均使用 ElasticsearchClient 高级 API。
     */
    @Bean
    public EmbeddingStore myEmbeddingStore(
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {

        String indexName = ragEsProps.getIndexName();

        VectorStoreUtil.diagnoseElasticsearch(esClient, indexName);
        VectorStoreUtil.deleteIndexIfNeeded(esClient, indexName, ragEsProps.isDeleteOnStartup());

        ElasticsearchEmbeddingStore embeddingStore = ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(indexName)
                .configuration(ElasticsearchConfigurationScript.builder().build())
                .build();

        DocumentIngestor.ingestKnowledgeBase(embeddingStore, embeddingModel);
        VectorStoreUtil.writeAfterVerify(esClient, indexName);
        return embeddingStore;
    }
}
