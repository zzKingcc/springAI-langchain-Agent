package com.zxcSpringAI;


import com.zxcSpringAI.model.RagElasticsearchProperties;
import com.zxcSpringAI.model.SpringElasticsearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({RagElasticsearchProperties.class, SpringElasticsearchProperties.class})
public class SpringRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringRagApplication.class, args);
    }
}
