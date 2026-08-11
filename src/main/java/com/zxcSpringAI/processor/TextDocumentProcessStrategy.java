package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.zxcSpringAI.exception.KnowledgeBaseException;
import com.zxcSpringAI.splitter.ChineseArticleDocumentSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文本类型文档处理策略
 */
@Slf4j
public class TextDocumentProcessStrategy implements DocumentProcessStrategy {

    /** 适用类型 */
    public static final List<String> TEXT_EXTENSIONS = List.of(
            "txt", "text", "md", "markdown"
    );

    /** ES terms 查询单次最大条数限制 */
    private static final int ES_TERMS_BATCH_SIZE = 10000;

    /** 百炼 text-embedding-v2 模型限制的单次请求最大行数 */
    private static final int EMBEDDING_BATCH_SIZE = 25;

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        int docCount = documents.size();

        log.info("[分片写入-{}][{}] 开始处理 {} 个文本类型文档", sourceTag, strategyName(), docCount);

        // 1. 分片 
        ChineseArticleDocumentSplitter splitter = new ChineseArticleDocumentSplitter();
        List<TextSegment> allSegments = splitter.splitAll(documents);
        log.info("[分片写入-{}][{}] 分片完成：{} 个原始文档 → {} 个 TextSegment，" +
                        "最小字符 {}，最大字符 {}，平均字符 {}",
                sourceTag,
                strategyName(),
                docCount,
                allSegments.size(),
                allSegments.stream().mapToInt(s -> s.text().length()).min().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).max().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).average().orElse(0d)
                );

        // 2. 根据内容生成文本段唯一 content_hash 
        for (TextSegment seg : allSegments) {
            String hash = computeContentHash(
                    seg.metadata().getString("file_name"),
                    seg.metadata().getString("section_title"),
                    seg.text()
            );
            seg.metadata().put("content_hash", hash);
        }

        // 3. 批次内去重 
        Map<String, TextSegment> uniqueSegments = new LinkedHashMap<>();
        for (TextSegment seg : allSegments) {
            String hash = seg.metadata().getString("content_hash");
            uniqueSegments.putIfAbsent(hash, seg);
        }
        int batchDupCount = allSegments.size() - uniqueSegments.size();
        if (batchDupCount > 0) {
            log.info("[分片写入-{}][{}] 执行批次内去重：从 {} 个原始片段中移除 {} 个重复片段，剩余 {} 个文本段",
                    sourceTag, strategyName(), allSegments.size(), batchDupCount, uniqueSegments.size());
        }

        // 4. ES 去重 
        Set<String> existingHashes = queryExistingHashes(esClient, indexName, uniqueSegments.keySet());
        List<TextSegment> newSegments = new ArrayList<>();
        for (TextSegment seg : uniqueSegments.values()) {
            String hash = seg.metadata().getString("content_hash");
            if (!existingHashes.contains(hash)) {
                newSegments.add(seg);
            }
        }
        int esDupCount = uniqueSegments.size() - newSegments.size();
        if (esDupCount > 0) {
            log.info("[分片写入-{}][{}] 执行ES片段去重：发现 {} 个片段已存在，跳过写入", sourceTag, strategyName(), esDupCount);
        }

        if (newSegments.isEmpty()) {
            log.info("[分片写入-{}][{}] 去重后无新增片段，跳过写入。", sourceTag, strategyName());
            return docCount;
        }

        // 5. 分批向量化 + 写入 受 text-embedding-v2 单次请求最大 25 行限制，超出会返回 400 InvalidParameter
        log.info("[分片写入-{}][{}] 开始向量化写入（共 {} 个片段，每批 {} 个）...",
                sourceTag, strategyName(), newSegments.size(), EMBEDDING_BATCH_SIZE);
        try {
            int total = newSegments.size();
            int written = 0;
            for (int start = 0; start < total; start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(start + EMBEDDING_BATCH_SIZE, total);
                List<TextSegment> batch = newSegments.subList(start, end);

                Response<List<Embedding>> embedResp = embeddingModel.embedAll(batch);
                List<Embedding> embeddings = embedResp.content();
                for (int i = 0; i < batch.size(); i++) {
                    embeddingStore.add(embeddings.get(i), batch.get(i));
                }
                written += batch.size();
                log.info("[分片写入-{}][{}] 向量化进度: {}/{}", sourceTag, strategyName(), written, total);
            }
            log.info("[分片写入-{}][{}] 写入完成，新增 {} 个片段。", sourceTag, strategyName(), newSegments.size());
        } catch (Exception e) {
            log.error("[分片写入-{}][{}] 向量化写入失败: {}", sourceTag, strategyName(), e.getMessage(), e);
        }

        return docCount;
    }

    @Override
    public List<String> supportedExtensions() {
        return TEXT_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "文本类型";
    }


    /**
     * 计算 content_hash：SHA-256(file_name + section_title + text)
     */
    private String computeContentHash(String fileName, String sectionTitle, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = (fileName == null ? "" : fileName) + "|"
                    + (sectionTitle == null ? "" : sectionTitle) + "|"
                    + (text == null ? "" : text);
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new KnowledgeBaseException("SHA-256 哈希计算失败，去重逻辑不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 分批 terms 查询索引中已存在的 content_hash。
     * 查询失败返回空集，不阻塞导入。
     */
    private Set<String> queryExistingHashes(ElasticsearchClient esClient,
                                            String indexName,
                                            Set<String> hashesToCheck) {
        if (hashesToCheck == null || hashesToCheck.isEmpty()) {
            return Set.of();
        }

        Set<String> existing = new HashSet<>();
        List<String> hashList = new ArrayList<>(hashesToCheck);

        for (int start = 0; start < hashList.size(); start += ES_TERMS_BATCH_SIZE) {
            int end = Math.min(start + ES_TERMS_BATCH_SIZE, hashList.size());
            List<String> chunk = hashList.subList(start, end);

            try {
                List<FieldValue> fieldValues = new ArrayList<>();
                for (String h : chunk) {
                    fieldValues.add(FieldValue.of(h));
                }

                Query termsQuery = new Query.Builder()
                        .terms(t -> t
                                .field("metadata.content_hash")
                                .terms(tf -> tf.value(fieldValues)))
                        .build();

                SearchResponse<Map> resp = esClient.search(s -> s
                                .index(indexName)
                                .size(chunk.size())
                                .source(src -> src.filter(f -> f.includes("metadata")))
                                .query(termsQuery),
                        Map.class);

                resp.hits().hits().forEach(h -> {
                    if (h.source() != null) {
                        Object metadataObj = h.source().get("metadata");
                        if (metadataObj instanceof Map) {
                            Object hash = ((Map<?, ?>) metadataObj).get("content_hash");
                            if (hash != null) {
                                existing.add(hash.toString());
                            }
                        }
                    }
                });
            } catch (Exception e) {
                log.warn("[分片去重-{}] 查询ES已有content_hash失败（索引可能不存在或无content_hash字段），"
                        + "跳过ES去重: {}", strategyName(), e.getMessage());
            }
        }

        return existing;
    }


    /**
     * 判断给定扩展名是否为文本类型（工厂调用用）
     */
    public static boolean isTextExtension(String ext) {
        if (ext == null || ext.isBlank()) return false;
        return TEXT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
