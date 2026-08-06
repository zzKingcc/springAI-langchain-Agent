package com.zxcSpringAI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 连接配置
 *
 * 对应 application.yaml 中 spring.elasticsearch.* 配置项，
 * 用于构建 RestClient 与 ElasticsearchClient 客户端。
 * 当服务器未开启鉴权时，username 与 password 可留空。
 */
@Data
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class SpringElasticsearchProperties {

    /** 服务端主机地址（IP 或域名） */
    private String host = "localhost";

    /** 服务端 HTTP 端口 */
    private int port = 9200;

    /** 协议（http 或 https） */
    private String scheme = "http";

    /** 连接用户名，未启用安全认证时可为 null */
    private String username;

    /** 连接密码，未启用安全认证时可为 null */
    private String password;

    /** 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** 读写超时（毫秒） */
    private int socketTimeout = 10000;

}