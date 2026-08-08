package com.zxcSpringAI.util;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zxcSpringAI.processor.DocumentProcessStrategy;
import com.zxcSpringAI.processor.DocumentProcessStrategyFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档导入工具（策略模式调度层）
 *
 * 提供两个入口：
 * - {@link #ingestKnowledgeBase}：从本地 classpath:ragDatabase 加载文档后导入；
 * - {@link #ingestExternalDocuments}：接收外部传入的文档列表进行导入（待实现）。
 *
 * 两者最终都调用 {@link #doIngest}，按文件扩展名通过 {@link DocumentProcessStrategyFactory}
 * 匹配对应的 {@link DocumentProcessStrategy} 执行具体处理逻辑：
 *
 * <ul>
 *   <li>文本类型：{@link com.zxcSpringAI.processor.TextDocumentProcessStrategy} — 实际实现（分片+去重+向量化写入）</li>
 *   <li>PDF 类型：{@link com.zxcSpringAI.processor.PdfDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>Word 类型：{@link com.zxcSpringAI.processor.WordDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>Excel 类型：{@link com.zxcSpringAI.processor.ExcelDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>PPT 类型：{@link com.zxcSpringAI.processor.PptDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>图片类型：{@link com.zxcSpringAI.processor.ImageDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>压缩包类型：{@link com.zxcSpringAI.processor.ArchiveDocumentProcessStrategy} — 占位，仅打日志</li>
 *   <li>未知类型：{@link com.zxcSpringAI.processor.UnknownDocumentProcessStrategy} — 兜底，仅打日志</li>
 * </ul>
 *
 * 扩展新文件类型只需两步：
 *   1. 在 processor 包下新建策略类，实现 {@link DocumentProcessStrategy}；
 *   2. 在 {@link DocumentProcessStrategyFactory#ALL_STRATEGIES} 注册新实例。
 */
@Slf4j
public class DocumentIngestor {

    private DocumentIngestor() {
    }

    // ==================== 公共入口 ====================

    /**
     * 从本地 classpath:ragDatabase 加载知识库文档并执行向量化写入。
     *
     * @param esClient       ES 客户端（用于去重查询）
     * @param indexName      ES 索引名
     * @param embeddingStore 向量存储实例
     * @param embeddingModel 文本嵌入模型
     * @return 实际处理（即匹配到策略）的文档总数
     */
    public static int ingestKnowledgeBase(ElasticsearchClient esClient,
                                          String indexName,
                                          EmbeddingStore embeddingStore,
                                          EmbeddingModel embeddingModel) {
        log.info("[知识库导入-本地] 开始从 classpath:ragDatabase 加载文档...");
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("ragDatabase");
        int docCount = documents == null ? 0 : documents.size();
        log.info("[知识库导入-本地] 加载到知识库文档数(raw): {}", docCount);

        if (docCount == 0) {
            return 0;
        }

        return doIngest(documents, esClient, indexName, embeddingStore, embeddingModel, "本地");
    }

    /**
     * 接收外部传入的文档列表并执行向量化写入。
     *
     * <p>适用场景：API 上传、用户手动提交、其他模块推送等非本地 classpath 来源的文档。</p>
     *
     * <p><b>TODO 后期实现要点：</b></p>
     * <ul>
     *   <li>将外部传入的文件/输入流转换为 LangChain4j {@link Document} 对象；</li>
     *   <li>对外部文档补充 file_name 等 metadata（外部来源可能没有）；</li>
     *   <li>调用 {@link #doIngest} 执行统一的策略分发（内部按扩展名自动选策略）。</li>
     * </ul>
     *
     * @param documents      外部传入的文档列表
     * @param esClient       ES 客户端（用于去重查询）
     * @param indexName      ES 索引名
     * @param embeddingStore 向量存储实例
     * @param embeddingModel 文本嵌入模型
     * @return 实际处理的文档总数
     */
    public static int ingestExternalDocuments(List<Document> documents,
                                              ElasticsearchClient esClient,
                                              String indexName,
                                              EmbeddingStore embeddingStore,
                                              EmbeddingModel embeddingModel) {
        // TODO: 外部文档导入的具体实现
        //  1. 校验 documents 非空
        //  2. 补充 metadata（file_name 等）
        //  3. 调用 doIngest 完成导入
        log.warn("[知识库导入-外部] 外部文档导入功能尚未实现，当前调用将被忽略。");
        return 0;
    }

    // ==================== 核心导入流程（策略模式分发） ====================

    /**
     * 统一的文档导入流程：按文件扩展名分组 → 按策略依次调用 process()。
     *
     * <p>每个策略的具体处理方式不同：文本类型会真正执行分片+去重+写入，
     * 非文本类型（PDF/Word/Excel/PPT/图片/压缩包/未知）仅输出占位日志，不做任何写入，
     * 后期需要支持时只需补全对应策略的 process() 实现即可。</p>
     *
     * @param documents      原始文档列表（可能混合多种文件类型）
     * @param esClient       ES 客户端（传给策略做去重查询等）
     * @param indexName      ES 索引名
     * @param embeddingStore 向量存储实例
     * @param embeddingModel 文本嵌入模型
     * @param sourceTag      来源标签（"本地"/"外部"），仅用于日志区分
     * @return 本次实际调用策略处理过的文档总数（含文本类型实际写入 + 非文本类型占位处理但未写入）
     */
    private static int doIngest(List<Document> documents,
                                ElasticsearchClient esClient,
                                String indexName,
                                EmbeddingStore embeddingStore,
                                EmbeddingModel embeddingModel,
                                String sourceTag) {
        int docCount = documents.size();

        // ===== 按扩展名分组到对应策略 =====
        Map<DocumentProcessStrategy, List<Document>> group =
                DocumentProcessStrategyFactory.groupByStrategy(documents);

        // ===== 打印分组统计 =====
        StringBuilder summary = new StringBuilder();
        summary.append("[知识库导入-").append(sourceTag).append("] 按文件类型分组：")
                .append("共 ").append(docCount).append(" 个文档 → ");
        int idx = 0;
        for (Map.Entry<DocumentProcessStrategy, List<Document>> e : group.entrySet()) {
            if (idx++ > 0) summary.append(", ");
            summary.append(e.getKey().strategyName())
                    .append('=').append(e.getValue().size());
        }
        log.info(summary.toString());

        // ===== 依次调用每个策略 =====
        int totalProcessed = 0;
        for (Map.Entry<DocumentProcessStrategy, List<Document>> e : group.entrySet()) {
            DocumentProcessStrategy strategy = e.getKey();
            List<Document> docsOfStrategy = e.getValue();
            try {
                int processed = strategy.process(
                        docsOfStrategy, esClient, indexName, embeddingStore, embeddingModel, sourceTag);
                totalProcessed += processed;
                log.debug("[知识库导入-{}] 策略[{}]处理完成：返回 {} 个文档",
                        sourceTag, strategy.strategyName(), processed);
            } catch (Exception ex) {
                // 单策略异常不影响其他策略继续执行
                log.error("[知识库导入-{}] 策略[{}]处理异常，已跳过该组：{}",
                        sourceTag, strategy.strategyName(), ex.getMessage(), ex);
            }
        }

        log.info("[知识库导入-{}] 全部分组处理结束，总处理文档数：{}", sourceTag, totalProcessed);
        return totalProcessed;
    }
}
