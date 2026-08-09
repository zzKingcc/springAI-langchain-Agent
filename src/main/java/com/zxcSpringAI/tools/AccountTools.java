package com.zxcSpringAI.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 账户工具
 *
 * <p>提供会员信息、积分查询、优惠券等假逻辑方法，后续接入 Agent 时替换为真实数据源。</p>
 */
public class AccountTools {

    private static final Logger log = LoggerFactory.getLogger(AccountTools.class);

    /**
     * 查询用户会员信息
     *
     * @param userId 用户ID
     * @return 会员等级、积分、优惠券
     */
    @Tool("查询用户的会员等级、积分余额、可用优惠券")
    public String memberInfo(String userId) {
        log.info("[账户工具] 查询会员信息：{}", userId);
        if (userId == null || userId.isBlank()) {
            return "请提供用户ID";
        }
        return String.format("""
                用户[%s] 会员信息：
                会员等级：金卡会员
                累计积分：2,380 分
                可用优惠券：满100减10（1张）、满200减30（1张）
                注册时间：2023-06-15""", userId);
    }

    /**
     * 积分查询
     *
     * @param userId 用户ID
     * @return 积分明细
     */
    @Tool("查询用户积分余额和近期积分变动")
    public String queryPoints(String userId) {
        log.info("[账户工具] 查询积分：{}", userId);
        if (userId == null || userId.isBlank()) {
            return "请提供用户ID";
        }
        return String.format("""
                用户[%s] 积分明细：
                当前积分：2,380 分
                近30天新增：+150 分（购物奖励）
                近30天消耗：-200 分（兑换 满100减10 优惠券）
                即将过期：0 分""", userId);
    }

    /**
     * 修改收货地址（假逻辑）
     *
     * @param userId  用户ID
     * @param newAddress 新地址
     * @return 操作结果
     */
    @Tool("修改用户的默认收货地址")
    public String updateAddress(String userId, String newAddress) {
        log.info("[账户工具] 修改地址：用户[{}] → {}", userId, newAddress);
        if (userId == null || userId.isBlank()) {
            return "请提供用户ID";
        }
        if (newAddress == null || newAddress.isBlank()) {
            return "请提供新地址";
        }
        return "用户[" + userId + "] 默认收货地址已更新为：" + newAddress;
    }
}