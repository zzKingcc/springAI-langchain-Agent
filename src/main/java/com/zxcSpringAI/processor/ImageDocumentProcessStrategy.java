package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 图片文档处理策略（占位实现，仅打日志）
 *
 * <p><b>TODO：后期实现方案（多模态）</b>：
 * <ul>
 *   <li>引入通义万相 / GPT-4V 等多模态模型，将图片转文字描述；</li>
 *   <li>对 OCR 友好的图片先跑 OCR 提取文字；</li>
 *   <li>生成描述后走同文本类型一样的 "分片→去重→向量化" 流程；</li>
 *   <li>保留 image_path / image_width / image_height metadata。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ImageDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> IMAGE_EXTENSIONS = List.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff", "svg", "heic", "raw"
    );

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[文档处理-{}][图片类型] ⚠ 文件[{}]当前暂不支持图片/多模态解析，已跳过，请后续补充多模态解析器后再导入。",
                    sourceTag, safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return IMAGE_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "图片类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
