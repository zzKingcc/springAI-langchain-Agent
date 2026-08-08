package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
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
 *
 * <p>支持扩展：.txt .md .markdown .text .csv .log .json .xml .html .htm</p>
 * <p>流程：ChineseArticleDocumentSplitter 中文分片 → 计算 content_hash → 批次去重 → ES去重 → embedAll 批量向量化写入</p>
 */
@Slf4j
public class TextDocumentProcessStrategy implements DocumentProcessStrategy {

    /** 文本类型后缀 */
    public static final List<String> TEXT_EXTENSIONS = List.of(
            "txt", "text", "md", "markdown", "csv", "log", "json", "xml", "html", "htm"
    );

    /** ES terms 查询单次最大条数（避免超出 ES terms_size 限制） */
    private static final int ES_TERMS_BATCH_SIZE = 10000;

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        int docCount = documents.size();

        log.info("[文档处理-{}][文本类型] 开始处理 {} 个文本类型文档", sourceTag, docCount);

        // 文档样例预览
        String sample = documents.get(0).text();
        log.info("[文档处理-{}][文本类型] 文档[0]预览(前200字): {}",
                sourceTag, sample.length() > 200 ? sample.substring(0, 200) + "..." : sample);

        // 维度自测
        try {
            int dim = embeddingModel.embed("维度自测").content().vector().length;
            log.info("[文档处理-{}][文本类型] EmbeddingModel 输出向量维度: {}", sourceTag, dim);
        } catch (Exception e) {
            log.error("[文档处理-{}][文本类型] EmbeddingModel 自测失败: {}", sourceTag, e.getMessage());
        }

        // ===== 1. 分片 =====
        ChineseArticleDocumentSplitter splitter = new ChineseArticleDocumentSplitter();
        List<TextSegment> allSegments = splitter.splitAll(documents);
        log.info("[文档处理-{}][文本类型] 分片完成：{} 原始文档 → {} 个 TextSegment，" +
                        "最小字符 {}，最大字符 {}，平均字符 {}",
                sourceTag,
                docCount,
                allSegments.size(),
                allSegments.stream().mapToInt(s -> s.text().length()).min().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).max().orElse(0),
                allSegments.stream().mapToInt(s -> s.text().length()).average().orElse(0d));

        // ===== 2. 计算 content_hash =====
        for (TextSegment seg : allSegments) {
            String hash = computeContentHash(
                    seg.metadata().getString("file_name"),
                    seg.metadata().getString("section_title"),
                    seg.text()
            );
            seg.metadata().put("content_hash", hash);
        }

        // ===== 3. 批次内去重 =====
        Map<String, TextSegment> uniqueSegments = new LinkedHashMap<>();
        for (TextSegment seg : allSegments) {
            String hash = seg.metadata().getString("content_hash");
            uniqueSegments.putIfAbsent(hash, seg);
        }
        int batchDupCount = allSegments.size() - uniqueSegments.size();
        if (batchDupCount > 0) {
            log.info("[文档处理-{}][文本类型] 批次内去重：{} 个片段中移除 {} 个重复，剩余 {} 个",
                    sourceTag, allSegments.size(), batchDupCount, uniqueSegments.size());
        }

        // ===== 4. ES 去重 =====
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
            log.info("[文档处理-{}][文本类型] ES去重：发现 {} 个片段已存在，跳过写入", sourceTag, esDupCount);
        }

        if (newSegments.isEmpty()) {
            log.info("[文档处理-{}][文本类型] 去重后无新增片段，跳过写入。", sourceTag);
            return docCount;
        }

        log.info("[文档处理-{}][文本类型] 去重后待写入 {} 个新片段（原始 {}，批次内重复 {}，ES已存在 {}）",
                sourceTag, newSegments.size(), allSegments.size(), batchDupCount, esDupCount);

        // ===== 5. 批量向量化 + 写入 =====
        log.info("[文档处理-{}][文本类型] 开始向量化写入...", sourceTag);
        try {
            Response<List<Embedding>> embedResp = embeddingModel.embedAll(newSegments);
            List<Embedding> embeddings = embedResp.content();
            for (int i = 0; i < newSegments.size(); i++) {
                embeddingStore.add(embeddings.get(i), newSegments.get(i));
            }
            log.info("[文档处理-{}][文本类型] 写入完成，新增 {} 个片段。", sourceTag, newSegments.size());
        } catch (Exception e) {
            log.error("[文档处理-{}][文本类型] 向量化写入失败: {}", sourceTag, e.getMessage(), e);
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

    // ==================== 去重辅助 ====================

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
            return Integer.toHexString((fileName + "|" + sectionTitle + "|" + text).hashCode());
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
                log.warn("[文档处理] 查询ES已有content_hash失败（索引可能不存在或无content_hash字段），"
                        + "跳过ES去重: {}", e.getMessage());
            }
        }

        return existing;
    }

    // ==================== 公开辅助 ====================

    /**
     * 判断给定扩展名是否为文本类型（工厂调用用）
     */
    public static boolean isTextExtension(String ext) {
        if (ext == null || ext.isBlank()) return false;
        return TEXT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
