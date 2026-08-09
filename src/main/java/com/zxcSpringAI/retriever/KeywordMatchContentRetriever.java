package com.zxcSpringAI.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.zxcSpringAI.exception.KnowledgeBaseException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 关键词精准匹配检索器（BM25）
 *
 * <p>对 ES 的 {@code text} 字段执行 match 查询，利用 IK 分词器进行中文分词后做 BM25 相关性打分。
 * 适用于精确术语、型号编号、阈值参数等向量检索容易漏召回的场景。</p>
 *
 * <p>检索字段：text（IK 分词后的全文匹配）+ section_title（章节标题加权匹配）</p>
 */
public class KeywordMatchContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordMatchContentRetriever.class);

    private final ElasticsearchClient esClient;
    private final String indexName;
    private final int maxResults;

    public KeywordMatchContentRetriever(
            ElasticsearchClient esClient,
            String indexName,
            int maxResults) {
        this.esClient = esClient;
        this.indexName = indexName;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(dev.langchain4j.rag.query.Query query) {
        String queryText = query.text();

        // multi_match 查询：text 字段权重 1.0，section_title 字段权重 2.0（标题命中优先级更高）
        Query matchQuery = new Query.Builder()
                .multiMatch(m -> m
                        .query(queryText)
                        .fields("text^1.0", "metadata.section_title^2.0")
                        .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields))
                .build();

        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(indexName)
                            .size(maxResults)
                            .source(src -> src.filter(f -> f.includes("text", "metadata")))
                            .query(matchQuery),
                    Map.class);

            List<Content> out = new ArrayList<>();
            resp.hits().hits().forEach(h -> {
                String text = "";
                if (h.source() != null && h.source().get("text") != null) {
                    text = h.source().get("text").toString();
                }
                if (!text.isBlank()) {
                    TextSegment segment = TextSegment.from(text);
                    // 将 ES BM25 分数写入 metadata，供后续分数融合使用
                    segment.metadata().put("_retrieval_score", h.score());
                    // 将 ES 元数据字段传递给上层，供 boost 计算使用
                    copyEsMetadata(h.source(), segment);
                    out.add(Content.from(segment));
                }
            });

            log.info("[ES关键词检索] 查询完成，关键词='{}'，设定{}条，命中{}条",
                    queryText.length() > 50 ? queryText.substring(0, 50) + "..." : queryText,
                    maxResults, out.size());
            return out;
        } catch (IOException e) {
            log.error("[ES关键词检索] 查询异常: {}", e.getMessage(), e);
            throw new KnowledgeBaseException("ES 关键词检索异常: " + e.getMessage(), e);
        }
    }

    /**
     * 从 ES source 中提取 file_name、section_title 等元数据写入 TextSegment metadata
     */
    @SuppressWarnings("unchecked")
    private void copyEsMetadata(Map<String, Object> source, TextSegment segment) {
        if (source == null) return;
        Object metadataObj = source.get("metadata");
        if (metadataObj instanceof Map) {
            Map<String, Object> meta = (Map<String, Object>) metadataObj;
            if (meta.get("file_name") != null) {
                segment.metadata().put("file_name", meta.get("file_name").toString());
            }
            if (meta.get("section_title") != null) {
                segment.metadata().put("section_title", meta.get("section_title").toString());
            }
        }
    }
}
