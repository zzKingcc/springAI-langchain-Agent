package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公司信息工具
 *
 * <p>提供公司介绍、客服信息、售后政策等假逻辑方法，后续接入 Agent 时替换为真实数据源。</p>
 */
public class CompanyTools {

    private static final Logger log = LoggerFactory.getLogger(CompanyTools.class);

    /**
     * 公司介绍
     *
     * @return 公司基本信息
     */
    @Tool("获取喜羊羊公司基本信息介绍")
    public String companyInfo() {
        log.info("[公司工具] 查询公司信息");
        return """
                喜羊羊（中国）文化创意有限公司
                成立时间：2015年
                主营业务：动漫IP周边产品设计、生产与销售
                品牌理念：让快乐触手可及～
                官网：www.xiyangyang.com
                客服热线：400-888-6666""";
    }

    /**
     * 售后政策查询
     *
     * @param category 售后类型（退换货/保修/投诉）
     * @return 对应政策
     */
    @Tool("查询售后政策，支持退换货、保修、投诉等类型")
    public String afterSalesPolicy(String category) {
        log.info("[公司工具] 查询售后政策：{}", category);
        if (category == null || category.isBlank()) {
            return """
                    喜羊羊公司售后政策：
                    退换货：7天无理由退换，15天质量问题换货
                    保修：电子产品1年，其他产品3个月
                    投诉：拨打 400-888-6666 或在线客服""";
        }

        if (category.contains("退换") || category.contains("退货")) {
            return "退换货政策：签收后 7 天内可无理由退换，15 天内质量问题可换货。退回商品需保持原包装完整，附赠品需一并退回。";
        }
        if (category.contains("保修")) {
            return "保修政策：电子产品享 1 年质保，其他产品享 3 个月质保。非人为损坏免费维修，人为损坏收取材料费。";
        }
        if (category.contains("投诉")) {
            return "投诉渠道：客服热线 400-888-6666（工作日 9:00-18:00），或在线客服 7×24 小时。我们会在 24 小时内回复处理。";
        }
        return "暂无「" + category + "」相关售后政策，您可以咨询退换货、保修或投诉";
    }

    /**
     * 客服工作时间
     *
     * @return 工作时间信息
     */
    @Tool("查询客服工作时间与联系方式")
    public String serviceHours() {
        log.info("[公司工具] 查询客服时间");
        return """
                喜羊羊客服中心：
                在线客服：7×24 小时
                电话客服：工作日 9:00-18:00
                热线：400-888-6666
                邮箱：service@xiyangyang.com""";
    }
}