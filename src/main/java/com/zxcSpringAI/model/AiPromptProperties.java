package com.zxcSpringAI.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 提示词配置模型
 */
@Data
@ConfigurationProperties(prefix = "ai.prompt")
public class AiPromptProperties {

    /** 系统提示词：定义角色、回答规则 */
    private String systemMessage;

}