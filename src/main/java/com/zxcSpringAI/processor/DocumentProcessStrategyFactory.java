package com.zxcSpringAI.processor;

import com.zxcSpringAI.exception.KnowledgeBaseException;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档处理策略工厂
 *
 * <p>注册所有已实现的 {@link DocumentProcessStrategy}，根据文件扩展名匹配对应策略。</p>
 * <p>扩展新类型时只需两步：</p>
 * <ol>
 *   <li>新建策略类实现 {@link DocumentProcessStrategy}；</li>
 *   <li>在 {@link #ALL_STRATEGIES} 列表中新增一个实例（顺序决定匹配优先级，文本在前）。</li>
 * </ol>
 */
@Slf4j
public class DocumentProcessStrategyFactory {

    /** 所有已注册的策略实例。顺序即匹配优先级：文本最先，最后走未知兜底。 */
    private static final List<DocumentProcessStrategy> ALL_STRATEGIES = List.of(
            new TextDocumentProcessStrategy(),    // .txt .md .html ...
            new PdfDocumentProcessStrategy(),     // .pdf
            new WordDocumentProcessStrategy(),    // .doc .docx
            new ExcelDocumentProcessStrategy(),   // .xls .xlsx
            new ImageDocumentProcessStrategy()   // .png .jpg ...
    );

    /** 扩展名 → 策略 的映射缓存（首次访问 build） */
    private static final Map<String, DocumentProcessStrategy> EXT_TO_STRATEGY;

    static {
        EXT_TO_STRATEGY = new HashMap<>();
        for (DocumentProcessStrategy strategy : ALL_STRATEGIES) {
            for (String ext : strategy.supportedExtensions()) {
                String lowerExt = ext.toLowerCase(Locale.ROOT);
                DocumentProcessStrategy prev = EXT_TO_STRATEGY.put(lowerExt, strategy);
                if (prev != null) {
                    throw new KnowledgeBaseException(
                            "[策略工厂] 扩展名冲突: ." + lowerExt + " 同时被 ["
                                    + prev.strategyName() + "] 和 [" + strategy.strategyName() + "] 覆盖，请检查 supportedExtensions() 列表。");
                }
            }
        }
    }

    /**
     * 根据文件名取扩展名匹配策略。
     *
     * <p>匹配规则：</p>
     * <ul>
     *   <li>有扩展名 → 根据 ALL_STRATEGIES 注册顺序匹配第一个命中的策略；</li>
     *   <li>无后缀 → 默认视为纯文本，走 {@link TextDocumentProcessStrategy}；</li>
     *   <li>未知后缀 → 返回 {@link UnknownDocumentProcessStrategy}，仅打日志不处理。</li>
     * </ul>
     */
    public static DocumentProcessStrategy resolve(String fileName) {
        String ext = extractExtension(fileName);
        if (ext == null) {
            // 无后缀：默认当作纯文本处理
            return ALL_STRATEGIES.stream()
                    .filter(s -> s instanceof TextDocumentProcessStrategy)
                    .findFirst()
                    .orElseThrow(() -> new KnowledgeBaseException("[策略工厂] 未注册 TextDocumentProcessStrategy"));
        }
        DocumentProcessStrategy s = EXT_TO_STRATEGY.get(ext.toLowerCase(Locale.ROOT));
        if (s != null) return s;
        return UnknownDocumentProcessStrategy.INSTANCE;
    }

    /**
     * 按策略分组一批 Document，按策略调用顺序返回（方便后续按策略顺序调用 process）。
     *
     * @param documents 待分组的 Document 列表
     * @return 策略 → 该策略对应的 Document 子列表（保留分组，未命中任何策略的走 Unknown）
     */
    public static Map<DocumentProcessStrategy, List<Document>> groupByStrategy(List<Document> documents) {
        Map<DocumentProcessStrategy, List<Document>> group = new LinkedHashMap<>();
        // 初始化分组容器：按 ALL_STRATEGIES 注册顺序创建空列表，最后加 UNKNOWN
        for (DocumentProcessStrategy s : ALL_STRATEGIES) {
            group.put(s, new ArrayList<>());
        }
        group.put(UnknownDocumentProcessStrategy.INSTANCE, new ArrayList<>());

        for (Document doc : documents) {
            String name = extractFileName(doc);
            DocumentProcessStrategy strategy = resolve(name);
            group.computeIfAbsent(strategy, k -> new ArrayList<>()).add(doc);
        }

        // 移除空组，减少上层循环输出
        group.entrySet().removeIf(e -> e.getValue().isEmpty());
        return group;
    }

    /**
     * 所有已注册的策略列表（只读），用于上层打印统计。
     */
    public static List<DocumentProcessStrategy> allStrategies() {
        return ALL_STRATEGIES;
    }

    // ==================== 辅助 ====================

    private static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1);
    }

    private static String extractFileName(Document doc) {
        try {
            String name = doc.metadata().getString("file_name");
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {
        }
        try {
            String src = doc.metadata().getString("source");
            if (src != null && !src.isBlank()) {
                int sep = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
                return sep >= 0 ? src.substring(sep + 1) : src;
            }
        } catch (Exception ignored) {
        }
        try {
            String abs = doc.metadata().getString("absolute_path");
            if (abs != null && !abs.isBlank()) {
                int sep = Math.max(abs.lastIndexOf('/'), abs.lastIndexOf('\\'));
                return sep >= 0 ? abs.substring(sep + 1) : abs;
            }
        } catch (Exception ignored) {
        }
        return "(unknown)";
    }
}
