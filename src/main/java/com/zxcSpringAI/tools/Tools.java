package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 天气查询工具
 *
 * <p>提供喜羊羊公司所在地（广州）的天气相关查询方法，均为假逻辑数据，
 * 后续接入 Agent 时替换为真实天气 API。</p>
 */
public class Tools {

    private static final Logger log = LoggerFactory.getLogger(Tools.class);

    /** 公司所在地 */
    private static final String CITY = "广州";

    @Tool("查询喜羊羊公司所在地的当前天气状况")
    public String queryWeather() {
        log.info("[天气工具] 查询{}天气", CITY);
        return CITY + "当前天气：多云转晴，气温 26°C ~ 33°C，东南风 2-3 级，湿度 72%";
    }

    @Tool("查询喜羊羊公司所在地今日最高温度")
    public String queryMaxTemperature() {
        log.info("[天气工具] 查询{}最高温度", CITY);
        return CITY + "今日最高温度：33°C";
    }

    @Tool("查询喜羊羊公司所在地今日最低温度")
    public String queryMinTemperature() {
        log.info("[天气工具] 查询{}最低温度", CITY);
        return CITY + "今日最低温度：26°C";
    }

    @Tool("查询喜羊羊公司所在地当前湿度")
    public String queryHumidity() {
        log.info("[天气工具] 查询{}湿度", CITY);
        return CITY + "当前湿度：72%";
    }

    @Tool("查询喜羊羊公司所在地当前风力")
    public String queryWind() {
        log.info("[天气工具] 查询{}风力", CITY);
        return CITY + "当前风力：东南风 2-3 级";
    }
}