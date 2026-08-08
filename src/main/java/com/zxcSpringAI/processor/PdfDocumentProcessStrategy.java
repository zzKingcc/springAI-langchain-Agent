package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * PDF 文档处理策略（占位实现，仅打日志）
 *
 * <p><b>TODO：后期实现方案</b>：
 * <ul>
 *   <li>引入 Apache PdfBox 或 LangChain4j 内置 PdfDocumentParser；</li>
 *   <li>按页码 / 段落切分，保留 page_num metadata；</li>
 *   <li>对扫描件 PDF 考虑 OCR 预处理；</li>
 *   <li>最终走同文本类型一样的 "分片→去重→向量化" 流程。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class PdfDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> PDF_EXTENSIONS = List.of("pdf");

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[文档处理-{}][PDF类型] ⚠ 文件[{}]当前暂不支持PDF解析，已跳过，请后续补充PDF解析器后再导入。",
                    sourceTag, safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return PDF_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "PDF类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
