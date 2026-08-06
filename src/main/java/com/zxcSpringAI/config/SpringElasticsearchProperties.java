package com.zxcSpringAI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application.yaml 中 spring.elasticsearch.* 自定义配置
 */
@Data
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class SpringElasticsearchProperties {

    /** ES 主机地址 */
    private String host = "localhost";

    /** ES 端口 */
    private int port = 9200;

    /** 协议 */
    private String scheme = "http";

    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;

    /** 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** 读取超时（毫秒） */
    private int socketTimeout = 10000;

}