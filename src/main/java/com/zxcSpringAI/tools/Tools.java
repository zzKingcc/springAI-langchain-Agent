package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.zxcSpringAI.util.TokenUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工具集
 *
 * <p>所有方法使用 {@code @Tool} 注解声明，由 Agent 编排服务根据用户意图自动调用。
 * 以 Spring Bean 形式注册，可注入其他组件（如 ContentRetriever、RedisTemplate 等）。</p>
 */
@Component
public class Tools {

    private static final Logger log = LoggerFactory.getLogger(Tools.class);

    /** 公司所在地 */
    private static final String CITY = "广州";

    private final ContentRetriever contentRetriever;

    public Tools(@Qualifier("myContentRetriever") ContentRetriever contentRetriever) {
        this.contentRetriever = contentRetriever;
    }

    // ==================== 天气工具 ====================

    @Tool("查询喜羊羊公司所在地（广州）的当前天气状况，当用户问'今天天气怎么样'、'外面热不热'、'需要带伞吗'等天气相关问题时调用")
    public String queryWeather() {
        log.info("[天气工具] 查询{}天气", CITY);
        String result = CITY + "当前天气：多云转晴，气温 26°C ~ 33°C，东南风 2-3 级，湿度 72%";
        TokenUsageTracker.recordToolCall("queryWeather", CITY, result);
        return result;
    }

    @Tool("查询喜羊羊公司所在地（广州）今日最高温度，当用户问'今天最高多少度'、'热不热'等温度相关问题时调用")
    public String queryMaxTemperature() {
        log.info("[天气工具] 查询{}最高温度", CITY);
        String result = CITY + "今日最高温度：33°C";
        TokenUsageTracker.recordToolCall("queryMaxTemperature", CITY, result);
        return result;
    }

    @Tool("查询喜羊羊公司所在地（广州）今日最低温度，当用户问'今天最低多少度'、'晚上冷不冷'等温度相关问题时调用")
    public String queryMinTemperature() {
        log.info("[天气工具] 查询{}最低温度", CITY);
        String result = CITY + "今日最低温度：26°C";
        TokenUsageTracker.recordToolCall("queryMinTemperature", CITY, result);
        return result;
    }

    @Tool("查询喜羊羊公司所在地（广州）当前湿度，当用户问'潮不潮湿'、'湿度多少'等湿度相关问题时调用")
    public String queryHumidity() {
        log.info("[天气工具] 查询{}湿度", CITY);
        String result = CITY + "当前湿度：72%";
        TokenUsageTracker.recordToolCall("queryHumidity", CITY, result);
        return result;
    }

    @Tool("查询喜羊羊公司所在地（广州）当前风力，当用户问'风大不大'、'几级风'等风力相关问题时调用")
    public String queryWind() {
        log.info("[天气工具] 查询{}风力", CITY);
        String result = CITY + "当前风力：东南风 2-3 级";
        TokenUsageTracker.recordToolCall("queryWind", CITY, result);
        return result;
    }

    // ==================== 知识库检索工具 ====================

    /**
     * 从知识库检索相关内容（向量 Top15 + 关键词 Top5 混合检索 + 分数融合重排序 Top10）
     *
     * <p>模型自主判断是否需要调用：纯闲聊类问题不会触发，业务知识类问题才会检索。</p>
     *
     * @param keyword 检索关键词或问题
     * @return 检索到的知识库内容片段（拼接为纯文本），无结果时返回提示
     */
    @Tool("从喜羊羊公司知识库中检索业务相关内容。当用户询问公司产品、服务、政策、规章制度、业务流程、操作指南等知识性问题时调用此工具，参数为用户问题的关键词或完整问题描述。非知识性问题（闲聊、天气、问候等）不要调用")
    public String searchKnowledgeBase(@P("检索关键词或问题") String keyword) {
        log.info("[知识库检索工具] 关键词：{}", keyword);
        try {
            List<Content> contents = contentRetriever.retrieve(new Query(keyword));
            if (contents == null || contents.isEmpty()) {
                log.info("[知识库检索工具] 未检索到相关内容");
                TokenUsageTracker.recordToolCall("searchKnowledgeBase", keyword, "未检索到相关内容");
                return "未检索到相关内容";
            }
            // 综合召回取 Top5，避免过长上下文挤占 memory 窗口
            int limit = Math.min(contents.size(), 5);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                Content c = contents.get(i);
                sb.append("【片段").append(i + 1).append("】\n");
                // 附带来源元数据
                String fileName = c.textSegment().metadata().getString("file_name");
                String sectionTitle = c.textSegment().metadata().getString("section_title");
                if (fileName != null) {
                    sb.append("来源：").append(fileName);
                    if (sectionTitle != null && !sectionTitle.isBlank()) {
                        sb.append(" > ").append(sectionTitle);
                    }
                    sb.append("\n");
                }
                sb.append(c.textSegment().text()).append("\n\n");
            }
            String result = sb.toString();
            log.info("[知识库检索工具] 检索到 {} 条相关内容，返回前 {} 条", contents.size(), limit);
            TokenUsageTracker.recordToolCall("searchKnowledgeBase", keyword, result);
            return result;
        } catch (Exception e) {
            log.error("[知识库检索工具] 检索异常：{}", e.getMessage(), e);
            String errorResult = "知识库检索服务暂时不可用，请稍后重试";
            TokenUsageTracker.recordToolCall("searchKnowledgeBase", keyword, errorResult);
            return errorResult;
        }
    }
}
