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
 * 文档策略匹配工厂
 */
@Slf4j
public class DocumentProcessStrategyFactory {

    /** 已注册策略实例 */
    private static final List<DocumentProcessStrategy> ALL_STRATEGIES = List.of(
            new TextDocumentProcessStrategy(),    // .txt .md .markdown .text
            new PdfDocumentProcessStrategy(),     // .pdf
    );

    /** 扩展名 → 策略 的映射缓存 */
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
     */
    public static DocumentProcessStrategy resolve(String fileName) {
        String ext = extractExtension(fileName);
        if (ext == null) {
            // 无后缀：默认 unknown
            return ALL_STRATEGIES.stream()
                    .filter(s -> s instanceof UnknownDocumentProcessStrategy)
                    .findFirst()
                    .orElseThrow(() -> new KnowledgeBaseException("[策略工厂] 未注册 UnknownDocumentProcessStrategy"));
        }
        DocumentProcessStrategy s = EXT_TO_STRATEGY.get(ext.toLowerCase(Locale.ROOT));
        if (s != null) return s;
        return UnknownDocumentProcessStrategy.INSTANCE;
    }

    /**
     * 按照文档划分构建各处理器扫描链。
     * 
     * @param documents 待分组的 Document 列表
     * @return
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
     * 所有已注册的策略列表
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
