package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 产品工具
 *
 * <p>提供产品查询、库存、价格等假逻辑方法，后续接入 Agent 时替换为真实数据源。</p>
 */
public class ProductTools {

    private static final Logger log = LoggerFactory.getLogger(ProductTools.class);

    /**
     * 按关键词搜索产品
     *
     * @param keyword 搜索关键词
     * @return 匹配的产品列表
     */
    @Tool("按关键词搜索产品，返回名称、价格、库存")
    public String searchProduct(String keyword) {
        log.info("[产品工具] 搜索产品：{}", keyword);
        if (keyword == null || keyword.isBlank()) {
            return "请输入搜索关键词";
        }

        String kw = keyword.toLowerCase();
        if (kw.contains("保温杯") || kw.contains("水杯")) {
            return """
                    喜羊羊联名保温杯 | ¥75.00 | 库存：128 件
                    懒羊羊可爱随行杯 | ¥49.00 | 库存：256 件
                    美羊羊樱花保温杯 | ¥89.00 | 库存：62 件""";
        }
        if (kw.contains("拼图") || kw.contains("玩具")) {
            return """
                    青青草原大拼图（1000片） | ¥28.00 | 库存：340 件
                    喜羊羊立体拼图 | ¥35.00 | 库存：180 件
                    羊村积木套装 | ¥128.00 | 库存：45 件""";
        }
        if (kw.contains("抱枕") || kw.contains("周边")) {
            return """
                    懒羊羊抱枕 | ¥39.90 | 库存：210 件
                    喜羊羊方枕 | ¥45.00 | 库存：175 件
                    羊村家族靠垫套装 | ¥99.00 | 库存：30 件""";
        }
        if (kw.contains("t恤") || kw.contains("衣服") || kw.contains("服装")) {
            return """
                    喜羊羊联名T恤 | ¥79.00 | 库存：320 件
                    美羊羊印花卫衣 | ¥129.00 | 库存：88 件
                    羊村主题连帽衫 | ¥159.00 | 库存：56 件""";
        }
        return "未找到与「" + keyword + "」相关的产品，请尝试其他关键词，如：保温杯、拼图、抱枕、T恤";
    }

    /**
     * 查询产品详情
     *
     * @param productName 产品名称
     * @return 产品详细信息
     */
    @Tool("查询指定产品的详细信息，包含规格、材质、售后等")
    public String productDetail(String productName) {
        log.info("[产品工具] 查询产品详情：{}", productName);
        if (productName == null || productName.isBlank()) {
            return "请提供产品名称";
        }

        if (productName.contains("保温杯")) {
            return """
                    产品：喜羊羊联名保温杯
                    规格：450ml / 304不锈钢内胆
                    颜色：天空蓝、草地绿、暖阳橙
                    保温时长：12小时
                    售后：7天无理由退换，1年质保
                    价格：¥75.00""";
        }
        if (productName.contains("拼图")) {
            return """
                    产品：青青草原大拼图
                    规格：1000片 / 成品 75×50cm
                    材质：加厚灰纸板
                    适用年龄：6岁+
                    售后：7天无理由退换
                    价格：¥28.00""";
        }
        return "未找到产品「" + productName + "」的详细信息";
    }

    /**
     * 查询产品价格
     *
     * @param productName 产品名称
     * @return 价格信息
     */
    @Tool("查询产品当前售价")
    public String queryPrice(String productName) {
        log.info("[产品工具] 查询价格：{}", productName);
        if (productName == null || productName.isBlank()) {
            return "请提供产品名称";
        }
        return "产品「" + productName + "」当前售价：¥75.00，会员享 9.5 折优惠";
    }
}