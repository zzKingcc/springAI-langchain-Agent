package com.zxcSpringAI.processor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 压缩包文档处理策略（占位实现，仅打日志）
 *
 * <p><b>TODO：后期实现方案</b>：
 * <ul>
 *   <li>JavaZip / Commons Compress 解包；</li>
 *   <li>递归遍历内部文件，按扩展名重新路由到其他策略（Text/Pdf/Word 等）；</li>
 *   <li>metadata 增加 archive_path / inner_path 标记来源；</li>
 *   <li>最终走同文本类型一样的 "分片→去重→向量化" 流程。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ArchiveDocumentProcessStrategy implements DocumentProcessStrategy {

    public static final List<String> ARCHIVE_EXTENSIONS = List.of(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"
    );

    @Override
    public int process(List<Document> documents,
                       ElasticsearchClient esClient,
                       String indexName,
                       EmbeddingStore embeddingStore,
                       EmbeddingModel embeddingModel,
                       String sourceTag) {
        for (Document doc : documents) {
            log.error("[文档处理-{}][压缩包类型] ⚠ 文件[{}]当前暂不支持压缩包解析，已跳过，请后续补充压缩包解包逻辑后再导入。",
                    sourceTag, safeFileName(doc));
        }
        return 0;
    }

    @Override
    public List<String> supportedExtensions() {
        return ARCHIVE_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "压缩包类型";
    }

    private String safeFileName(Document doc) {
        try {
            return doc.metadata().getString("file_name");
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
