package com.zxcSpringAI.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 提示词配置模型
 *
 * <p>系统提示词从 application.yaml 的 ai.prompt 段读取，运行时通过
 * AiServices.builder().systemMessageProvider() 注入。</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.prompt")
public class AiPromptProperties {

    /** 系统提示词：定义角色、回答规则 */
    private String systemMessage;

}