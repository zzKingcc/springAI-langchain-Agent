package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工具集
 *
 * <p>所有方法使用 {@code @Tool} 注解声明，由 Agent 编排服务根据用户意图自动调用。
 * 以 Spring Bean 形式注册，可注入其他组件（如 RedisTemplate、Service 等）。</p>
 */
@Component
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
