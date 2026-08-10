package com.zxcSpringAI.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户输入安全检测与过滤工具
 */
public final class InputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizer.class);

    /** 用户输入最大长度（字符） */
    private static final int MAX_INPUT_LENGTH = 2000;

    //1、指令覆盖检测
    private static final Pattern[] INSTRUCTION_OVERRIDE = {
            Pattern.compile("忽略.{0,10}(上[面文]|之前|此前|以上).{0,10}(指令|规则|提示|要求|约束|限制|对话|内容|说明)"),
            Pattern.compile("(忘记|抛弃|无视|清除|删除|重置).{0,10}(之前|上[面文]|此前|以上).{0,10}(指令|规则|记忆|对话|上下文|历史)"),
            Pattern.compile("从现在开始.{0,20}(你[是叫]|你是|现在你)"),
            Pattern.compile("ignore.{0,10}(all|previous|above|prior).{0,10}(instruction|rule|prompt|command|directive|constraint|context|content)"),
            Pattern.compile("(forget|discard|disregard|override|overwrite|reset|clear).{0,10}(previous|above|prior|all).{0,10}(instruction|rule|prompt|memory|context)"),
            Pattern.compile("you are now.{0,30}(not|no longer|instead)"),
            Pattern.compile("new (system |)instruction"),
            Pattern.compile("你不再.{0,10}(是|需要|应该)"),
            Pattern.compile("override.{0,10}(system|instruction|prompt|rule)"),
    };

    //2、角色混淆检测
    private static final Pattern[] ROLE_CONFUSION = {
            Pattern.compile("(?i)^\\s*(system|assistant|user|function|tool)\\s*[:：]"),
            Pattern.compile("(?i)\\[system\\]|\\[assistant\\]|\\[user\\]"),
            Pattern.compile("SystemMessage\\s*[:：]"),
            Pattern.compile("(?i)role\\s*[:：]\\s*(system|assistant)"),
            Pattern.compile("你是.{0,5}(系统|AI|模型|GPT|LLM|大模型|人工智能)"),
            Pattern.compile("you are.{0,5}(system|AI|model|GPT|LLM)"),
    };

    //3、分隔符注入检测
    private static final Pattern[] DELIMITER_INJECTION = {
            Pattern.compile("(---|===|___|\\*\\*\\*|###)\\s*(system|instruction|命令|指令|规则|提示)"),
            Pattern.compile("(system|instruction|命令|指令|规则|提示)\\s*(---|===|___|\\*\\*\\*|###)"),
            Pattern.compile("(?m)^\\s*#{1,3}\\s*(system|指令|规则|提示|命令)"),
    };

    //4、系统提示词窃取检测
    private static final Pattern[] PROMPT_EXTRACTION = {
            Pattern.compile("(输出|打印|显示|重复|复述|告诉我|说出|透露|泄露|展示).{0,15}(系统提示词|system.{0,5}prompt|系统指令|你的指令|你的规则|你的设定|你的角色|你的人设)"),
            Pattern.compile("(repeat|print|output|show|display|tell|reveal|leak|dump).{0,15}(system.{0,5}prompt|instruction|your.{0,5}rule|your.{0,5}setting|your.{0,5}role)"),
            Pattern.compile("(你的.{0,5}prompt|你的.{0,5}提示词).{0,5}(是|什么)"),
            Pattern.compile("what.{0,5}(is|are).{0,5}your.{0,5}(instruction|prompt|rule|system)"),
            Pattern.compile("(把你|把你自己的).{0,10}(提示词|指令|规则|prompt).{0,5}(发|给|输出|打印)"),
            Pattern.compile("(翻译|转换|编码).{0,10}(提示词|prompt|指令)"),
    };

    //5、编码绕过检测
    private static final Pattern[] ENCODING_BYPASS = {
            Pattern.compile("(base64|unicode|hex|url.{0,5}encode|utf.{0,5}encode).{0,20}(decode|解码|解析)"),
            Pattern.compile("用.{0,5}(base64|unicode|编码).{0,10}(输出|翻译|回复|回答)"),
    };

    private InputSanitizer() {}

    /**
     * 检测用户输入是否包含注入攻击特征
     * @param input 用户输入
     * @return 命中检测规则时必须 reject
     */
    public static boolean isMalicious(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String lower = input.toLowerCase().trim();

        for (Pattern p : INSTRUCTION_OVERRIDE) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到指令覆盖攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : ROLE_CONFUSION) {
            if (p.matcher(input).find()) {
                log.warn("[输入安全] 检测到角色混淆攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : DELIMITER_INJECTION) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到分隔符注入攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : PROMPT_EXTRACTION) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到提示词窃取攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : ENCODING_BYPASS) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到编码绕过攻击: {}", truncate(input, 100));
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗用户输入
     * @param input 原始用户输入
     * @return 清洗后的安全文本
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String cleaned = input.trim()
                // 统一换行符
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                // 去除零宽字符（Unicode 隐形注入）
                .replaceAll("[\\u200B-\\u200F\\u2028-\\u202F\\uFEFF\\u00AD]", "")
                // 多行压缩为单行（防止用换行构造"角色"前缀）
                .replace("\n", " ");

        if (cleaned.length() > MAX_INPUT_LENGTH) {
            log.warn("[输入安全] 输入超长被截断: {} → {}", cleaned.length(), MAX_INPUT_LENGTH);
            cleaned = cleaned.substring(0, MAX_INPUT_LENGTH);
        }
        return cleaned;
    }

    /**
     * 检测 + 清洗一步完成
     * @param input 原始用户输入
     * @return 清洗后的安全文本
     * @throws IllegalArgumentException 如果检测到注入攻击
     */
    public static String validate(String input) {
        if (isMalicious(input)) {
            throw new IllegalArgumentException("输入包含不安全内容，已被拦截");
        }
        return sanitize(input);
    }

    /**
     * 检测命中规则列表，返回所有命中的规则描述
     */
    public static List<String> detectDetails(String input) {
        List<String> hits = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return hits;
        }
        String lower = input.toLowerCase().trim();

        checkPatterns(lower, INSTRUCTION_OVERRIDE, "指令覆盖", hits);
        checkPatterns(input, ROLE_CONFUSION, "角色混淆", hits);
        checkPatterns(lower, DELIMITER_INJECTION, "分隔符注入", hits);
        checkPatterns(lower, PROMPT_EXTRACTION, "提示词窃取", hits);
        checkPatterns(lower, ENCODING_BYPASS, "编码绕过", hits);

        return hits;
    }

    private static void checkPatterns(String input, Pattern[] patterns, String category, List<String> hits) {
        for (Pattern p : patterns) {
            if (p.matcher(input).find()) {
                hits.add(category + ": " + p.pattern());
            }
        }
    }

    private static String truncate(String input, int maxLen) {
        return input.length() <= maxLen ? input : input.substring(0, maxLen) + "...";
    }
}