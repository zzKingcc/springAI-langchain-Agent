package com.zxcSpringAI.retriever;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组合检索器
 * 1、向量检索
 * 2、关键词检索
 * 3、分数融合重排序）
 */
public class CompositeContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(CompositeContentRetriever.class);

    /** 向量检索权重 */
    private static final double VECTOR_WEIGHT = 0.6;
    /** 关键词检索权重 */
    private static final double KEYWORD_WEIGHT = 0.4;
    /** 标题命中 boost */
    private static final double TITLE_BOOST = 0.15;
    /** 文件名命中 boost */
    private static final double FILE_NAME_BOOST = 0.1;
    /** 重排序后最终返回条数 */
    private static final int TOP_N = 10;

    /** 向量检索分数 metadata key */
    static final String SCORE_KEY = "_retrieval_score";

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

        // 合并去重 + 记录分数来源
        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();
        String queryText = query.text();

        // 向量结果优先写入
        for (Content c : vectorResults) {
            String hash = hashContent(c);
            double score = extractScore(c);
            scoreMap.putIfAbsent(hash, new ScoreEntry(c, score, null, queryText));
        }

        // 关键词结果补充（已存在的不覆盖，保留向量优先）
        int keywordAdded = 0;
        for (Content c : keywordResults) {
            String hash = hashContent(c);
            double score = extractScore(c);
            if (scoreMap.containsKey(hash)) {
                // 已有向量结果，补充关键词分数
                scoreMap.get(hash).keywordScore = score;
            } else {
                scoreMap.put(hash, new ScoreEntry(c, null, score, queryText));
                keywordAdded++;
            }
        }

        // 分数融合
        if (scoreMap.isEmpty()) {
            return new ArrayList<>();
        }

        List<ScoreEntry> entries = new ArrayList<>(scoreMap.values());
        normalize(entries);
        computeFusedScores(entries);

        // 按融合分数降序排序，取 TopN
        entries.sort(Comparator.comparingDouble(e -> -e.fusedScore));
        int topN = Math.min(TOP_N, entries.size());
        List<ScoreEntry> topEntries = entries.subList(0, topN);

        List<Content> result = topEntries.stream()
                .map(e -> e.content)
                .collect(Collectors.toList());

        log.info("[组合检索] 向量命中{}条，关键词命中{}条，去重后{}条，融合重排Top{}",
                vectorResults.size(), keywordResults.size(), scoreMap.size(), topN);

        return result;
    }


    /**
     * 对向量分数和关键词分数分别做 min-max 归一化
     */
    private void normalize(List<ScoreEntry> entries) {
        // 向量分数归一化
        double vecMin = Double.MAX_VALUE, vecMax = Double.MIN_VALUE;
        for (ScoreEntry e : entries) {
            if (e.vectorScore != null) {
                vecMin = Math.min(vecMin, e.vectorScore);
                vecMax = Math.max(vecMax, e.vectorScore);
            }
        }
        for (ScoreEntry e : entries) {
            if (e.vectorScore != null) {
                e.normVectorScore = (vecMax == vecMin) ? 1.0
                        : (e.vectorScore - vecMin) / (vecMax - vecMin);
            }
        }

        // 关键词分数归一化
        double kwMin = Double.MAX_VALUE, kwMax = Double.MIN_VALUE;
        for (ScoreEntry e : entries) {
            if (e.keywordScore != null) {
                kwMin = Math.min(kwMin, e.keywordScore);
                kwMax = Math.max(kwMax, e.keywordScore);
            }
        }
        for (ScoreEntry e : entries) {
            if (e.keywordScore != null) {
                e.normKeywordScore = (kwMax == kwMin) ? 1.0
                        : (e.keywordScore - kwMin) / (kwMax - kwMin);
            }
        }
    }

    /**
     * 计算融合分数：加权求和 + boost
     */
    private void computeFusedScores(List<ScoreEntry> entries) {
        for (ScoreEntry e : entries) {
            double fused = 0.0;
            // 加权求和：缺失的分数来源视为 0 分
            fused += VECTOR_WEIGHT * (e.normVectorScore != null ? e.normVectorScore : 0.0);
            fused += KEYWORD_WEIGHT * (e.normKeywordScore != null ? e.normKeywordScore : 0.0);

            // 标题命中 boost
            String title = e.content.textSegment().metadata().getString("section_title");
            if (title != null && !title.isBlank() && containsAnyKeyword(title, e.queryText)) {
                fused += TITLE_BOOST;
            }

            // 文件名命中 boost
            String fileName = e.content.textSegment().metadata().getString("file_name");
            if (fileName != null && !fileName.isBlank() && containsAnyKeyword(fileName, e.queryText)) {
                fused += FILE_NAME_BOOST;
            }

            e.fusedScore = fused;
        }
    }

    /**
     * 从 Content metadata 中提取 ES 检索分数
     */
    private double extractScore(Content content) {
        try {
            return content.textSegment().metadata().getFloat(SCORE_KEY);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 判断文本中是否包含查询词的任意关键词（中文按单字/词匹配，英文按空格分词）
     */
    private boolean containsAnyKeyword(String text, String query) {
        if (text == null || query == null) return false;
        String lowerText = text.toLowerCase();
        // 提取查询中的关键词：中文字符序列、英文单词
        Set<String> keywords = extractKeywords(query);
        for (String kw : keywords) {
            if (kw.length() >= 2 && lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从查询文本中提取关键词集合
     */
    private Set<String> extractKeywords(String query) {
        // 简单策略：按空格和标点分割，过滤短词
        return Set.of(query.toLowerCase().split("[\\s，。！？、；：\"'（）《》\\[\\]【】,.!?;:()]+"));
    }

    //去重辅助
    private String hashContent(Content content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.textSegment().text().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return content.textSegment().text();
        }
    }

    //内部数据结构
    /**
     * 单条检索结果的分数记录，用于融合计算
     */
    private static class ScoreEntry {
        final Content content;
        final String queryText;
        Double vectorScore;
        Double keywordScore;
        Double normVectorScore;
        Double normKeywordScore;
        double fusedScore;

        ScoreEntry(Content content, Double vectorScore, Double keywordScore, String queryText) {
            this.content = content;
            this.vectorScore = vectorScore;
            this.keywordScore = keywordScore;
            this.queryText = queryText;
        }
    }
}
