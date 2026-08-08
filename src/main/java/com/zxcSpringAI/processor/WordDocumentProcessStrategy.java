package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Office Word 文档处理策略（占位实现，仅打日志）
 *
 * <p><b>TODO：后期实现方案</b>：
 * <ul>
 *   <li>引入 Apache POI 解析 .doc/.docx 内容；</li>
 *   <li>保留标题层级样式信息作为 section_title；</li>
 *   <li>处理表格、页眉页脚、批注等特殊内容；</li>
 *   <li>最终走同文本类型一样的 "分片→去重→向量化" 流程。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class WordDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> WORD_EXTENSIONS = List.of("doc", "docx");

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[文档处理-{}][Word类型] ⚠ 文件[{}]当前暂不支持Word解析，已跳过，请后续补充Word解析器后再导入。",
                    sourceTag, safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return WORD_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Word类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
