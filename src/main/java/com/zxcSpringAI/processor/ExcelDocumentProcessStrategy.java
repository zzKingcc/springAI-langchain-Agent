package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Office Excel 文档处理策略（占位实现，仅打日志）
 *
 * <p><b>TODO：后期实现方案</b>：
 * <ul>
 *   <li>引入 Apache POI / EasyExcel 解析 .xls/.xlsx；</li>
 *   <li>按 Sheet + 行号切分，表头与每行内容组合成语义片段；</li>
 *   <li>保留 sheet_name / row_num / column_headers 等 metadata；</li>
 *   <li>最终走同文本类型一样的 "分片→去重→向量化" 流程。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ExcelDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> EXCEL_EXTENSIONS = List.of("xls", "xlsx", "xlsm", "xlsb");

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[文档处理-{}][Excel类型] ⚠ 文件[{}]当前暂不支持Excel解析，已跳过，请后续补充Excel解析器后再导入。",
                    sourceTag, safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return EXCEL_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Excel类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
