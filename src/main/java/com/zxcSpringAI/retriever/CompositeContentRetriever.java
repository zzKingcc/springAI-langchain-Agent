package com.zxcSpringAI.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合检索器（向量检索 + 关键词检索）
 *
 * <p>同时调用向量相似度检索器和关键词精准匹配检索器，将两路结果合并去重后返回。
 * 向量检索擅长语义泛化，关键词检索擅长精确术语命中，两者互补提升召回覆盖率。</p>
 *
 * <p>去重策略：按 Content 文本内容做 SHA-256 快速比对，相同文本只保留第一个出现的（向量检索结果优先）。</p>
 */
public class CompositeContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(CompositeContentRetriever.class);

    private final ContentRetriever vectorRetriever;
    private final ContentRetriever keywordRetriever;

    public CompositeContentRetriever(ContentRetriever vectorRetriever,
                                     ContentRetriever keywordRetriever) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> vectorResults = new ArrayList<>();
        List<Content> keywordResults = new ArrayList<>();

        // 向量检索（异常不中断，返回空列表）
        try {
            vectorResults = vectorRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("[组合检索] 向量检索异常，仅使用关键词结果: {}", e.getMessage());
        }

        // 关键词检索（异常不中断，返回空列表）
        try {
            keywordResults = keywordRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("[组合检索] 关键词检索异常，仅使用向量结果: {}", e.getMessage());
        }

        // 合并去重：向量结果在前，关键词结果在后，相同文本去重
        Map<String, Content> merged = new LinkedHashMap<>();
        int vectorDup = 0;
        for (Content c : vectorResults) {
            String key = hashContent(c);
            if (merged.putIfAbsent(key, c) != null) {
                vectorDup++;
            }
        }
        int keywordDup = 0;
        int keywordAdded = 0;
        for (Content c : keywordResults) {
            String key = hashContent(c);
            if (merged.putIfAbsent(key, c) != null) {
                keywordDup++;
            } else {
                keywordAdded++;
            }
        }

        log.info("[组合检索] 向量命中{}条，关键词命中{}条，去重后合并{}条"
                        + "（向量内重复{}，关键词新增{}，关键词重复{}）",
                vectorResults.size(), keywordResults.size(), merged.size(),
                vectorDup, keywordAdded, keywordDup);

        return new ArrayList<>(merged.values());
    }

    /**
     * 用文本内容本身做 hash 去重（不涉及外部依赖，速度快）
     */
    private String hashContent(Content content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.textSegment().text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // fallback: 直接用文本（极端情况，SHA-256 不可能失败）
            return content.textSegment().text();
        }
    }
}
