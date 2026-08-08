package com.zxcSpringAI.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.zxcSpringAI.model.SpringElasticsearchProperties;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties(SpringElasticsearchProperties.class)
public class ElasticsearchConfig {

    /**
     * ES 通信连接客户端
     */
    @Bean(destroyMethod = "close")
    public RestClient restClient(SpringElasticsearchProperties esProps) {
        HttpHost host = new HttpHost(esProps.getHost(), esProps.getPort(), esProps.getScheme());
        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(cb -> cb
                        .setConnectTimeout(esProps.getConnectTimeout())
                        .setSocketTimeout(esProps.getSocketTimeout()));

        //指定了用户和密码时，添加到连接客户端中。
        String user = esProps.getUsername();
        String pwd = esProps.getPassword();
        if (user != null && !user.isBlank() && pwd != null && !pwd.isBlank()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pwd));
            builder.setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(cp));
        }
        return builder.build();
    }

    /**
     * ES 解析封装客户端
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
