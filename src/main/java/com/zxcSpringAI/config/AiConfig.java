package com.zxcSpringAI.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zxcSpringAI.model.RagElasticsearchProperties;
import com.zxcSpringAI.retriever.CompositeContentRetriever;
import com.zxcSpringAI.retriever.KeywordMatchContentRetriever;
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
 * AI 配置类
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
     * 组合检索器（向量检索 Top15 + 关键词检索 Top5）
     *
     * <p>向量检索负责语义泛化召回，关键词检索负责精确术语/型号/编号命中，两路结果合并去重后返回。
     * 任一路检索异常不中断另一路，保证可用性。</p>
     */
    @Bean
    public ContentRetriever myContentRetriever(
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {

        // 向量检索：余弦相似度，Top15，minScore=0.2
        NativeScriptScoreContentRetriever vectorRetriever = new NativeScriptScoreContentRetriever(
                esClient,
                ragEsProps.getIndexName(),
                embeddingModel,
                15,
                0.2
        );

        // 关键词检索：BM25 match，Top5
        KeywordMatchContentRetriever keywordRetriever = new KeywordMatchContentRetriever(
                esClient,
                ragEsProps.getIndexName(),
                5
        );

        return new CompositeContentRetriever(vectorRetriever, keywordRetriever);
    }

    /**
     * 向量存储：
     * 1) 判断 ES 环境状态，根据传入开关决定是否重建索引；
     * 2) 构建基于 script_score 的 ElasticsearchEmbeddingStore；
     * 3) 加载本地知识库文档，向量写入 ES 索引；
     * 4) 写入完成后执行索引校验。
     */
    @Bean
    public EmbeddingStore myEmbeddingStore(
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {

        String indexName = ragEsProps.getIndexName();

        VectorStoreUtil.diagnoseElasticsearch(esClient, indexName);
        VectorStoreUtil.deleteIndexIfNeeded(esClient, indexName, ragEsProps.isDeleteOnStartup());
        // 删除旧索引后、LangChain4j 构建前，创建带 IK 分词器的自定义 mapping（支持后续混合检索）
        VectorStoreUtil.createIndexWithIkMapping(esClient, indexName, 1536);

        ElasticsearchEmbeddingStore embeddingStore = ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(indexName)
                .configuration(ElasticsearchConfigurationScript.builder().build())
                .build();

        DocumentIngestor.ingestKnowledgeBase(esClient, indexName, embeddingStore, embeddingModel);
        VectorStoreUtil.writeAfterVerify(esClient, indexName);
        return embeddingStore;
    }
}
