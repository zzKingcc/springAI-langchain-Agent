package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单工具
 *
 * <p>提供订单查询、物流追踪、退款状态等假逻辑方法，后续接入 Agent 时替换为真实数据源。</p>
 */
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    /** 订单号前缀 */
    private static final String PREFIX = "XY";

    /**
     * 按订单号查询订单详情
     *
     * @param orderId 订单号，如 XY20240101001
     * @return 订单信息摘要
     */
    @Tool("根据订单号查询订单详情，包含下单时间、商品、金额、状态")
    public String queryOrder(String orderId) {
        log.info("[订单工具] 查询订单：{}", orderId);
        if (orderId == null || !orderId.startsWith(PREFIX)) {
            return "未找到订单 " + orderId + "，请确认订单号格式（如 XY20240101001）";
        }
        return String.format("""
                订单号：%s
                下单时间：2024-01-15 14:30:00
                商品：喜羊羊联名保温杯 ×2、青青草原拼图 ×1
                金额：¥158.00（含运费 ¥8.00）
                状态：已签收
                物流单号：SF1234567890""", orderId);
    }

    /**
     * 按物流单号追踪物流
     *
     * @param trackingNo 物流单号
     * @return 物流轨迹
     */
    @Tool("根据物流单号查询物流轨迹")
    public String trackLogistics(String trackingNo) {
        log.info("[订单工具] 追踪物流：{}", trackingNo);
        if (trackingNo == null || trackingNo.isBlank()) {
            return "请提供有效的物流单号";
        }
        return String.format("""
                物流单号：%s
                承运商：顺丰速运
                最新状态：运输中
                当前位置：北京分拣中心
                预计送达：2024-01-18""", trackingNo);
    }

    /**
     * 查询最近订单列表
     *
     * @param userId 用户ID
     * @return 最近订单摘要
     */
    @Tool("查询用户最近的订单列表")
    public String recentOrders(String userId) {
        log.info("[订单工具] 查询用户[{}]最近订单", userId);
        if (userId == null || userId.isBlank()) {
            return "请提供用户ID";
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return String.format("""
                用户[%s] 最近订单（查询时间：%s）：
                1. XY20260105001 | 喜羊羊联名T恤 ×1 | ¥79.00 | 已签收
                2. XY20260108002 | 青青草原拼图 ×2 | ¥56.00 | 配送中
                3. XY20260109003 | 懒羊羊抱枕 ×1 | ¥39.90 | 待发货""", userId, now);
    }

    /**
     * 查询退款状态
     *
     * @param orderId 订单号
     * @return 退款进度
     */
    @Tool("查询订单的退款/售后状态")
    public String refundStatus(String orderId) {
        log.info("[订单工具] 查询退款状态：{}", orderId);
        if (orderId == null || !orderId.startsWith(PREFIX)) {
            return "未找到订单 " + orderId;
        }

        Map<String, String> mockData = new LinkedHashMap<>();
        mockData.put("XY20260105001", "退款已完成，¥79.00 已退回原支付账户");
        mockData.put("XY20260108002", "退款处理中，预计 3-5 个工作日到账");
        mockData.put("XY20260109003", "暂无退款记录");

        String result = mockData.get(orderId);
        if (result == null) {
            return "订单 " + orderId + " 暂无退款记录";
        }
        return "订单 " + orderId + " 退款状态：" + result;
    }
}