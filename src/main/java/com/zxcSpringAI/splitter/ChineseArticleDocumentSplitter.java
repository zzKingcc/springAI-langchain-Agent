package com.zxcSpringAI.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文文章文档分片器
 *
 * 通用适配各类中文纯文本（.txt/.md导出的纯文本等），与行业无关。
 * 分片策略：
 * ① 先用正则匹配中文/数字/字母/圆圈号/方括号等多种常见的编号标题行，
 *    按"自然章节/段落"切块，尽量保留语义完整性；
 * ② 每个章节块字符数超过阈值时，再用 LangChain4j 内置的 recursive splitter 兜底切分；
 * ③ 每个切分后 TextSegment 的 metadata 注入：来源文件名(file_name) + 所属章节标题(section_title)，
 *    便于检索命中时模型能感知该片段在原文中的结构位置，提升引用准确性。
 *
 * 支持的标题行示例（持续扩展中）：
 *   一、二、三、  十、百、
 *   第一章 / 第二节 / 第三条 / 第一部分 / 第四篇
 *   1.  2.3  4.5.6   1、  2、  3．
 *   (1)  (2)  (三)
 *   ① ② ③  ⑪ ⑫
 *   【一】  【摘要】  【结论】
 *   ##  ###  ####（Markdown 标题，兼容导出的 txt）
 *   A.  B.  C. （大写字母 + 点，外文翻译类文档常用）
 *   附录A / 附录一 / 附表 3
 */
public class ChineseArticleDocumentSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChineseArticleDocumentSplitter.class);

    // ===== 中文编号标题行正则：覆盖尽可能多的通用编号形式 =====
    // 设计原则：宁可多匹配，不漏章节；误判的小片段可以靠后续相似度过滤。
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^\\s*" +
            "(?:" +
              // 1) 中文大写数字+顿号/点：一、 三． 十、 百二、
              "[一二三四五六七八九十百千零〇两]+[、.．]\\s*" +

              // 2) "第X章/节/课/条/部分/篇/卷/编/步/讲/集/回"：第一章 第三步 第二讲
              "|第[一二三四五六七八九十百千零〇两0-9]+[章节课条部分篇卷编步讲集回道单元章节季度册期]+\\s*" +

              // 3) 数字编号（可选多段）：1.  2.1  3.4.5   1、   2．
              "|\\d+(?:\\.\\d+)*[、.．]?\\s+" +

              // 4) 圆括号数字/中文：(1) (2) (三) (十二)
              "|[(（][\\d一二三四五六七八九十百千零〇两]+[)）]\\s*" +

              // 5) 圆圈数字：①~⑳ ㊱~㊿（Unicode 圆圈数字区，直接用范围）
              "|[\\u2460-\\u2473\\u3251-\\u325F\\u32B1-\\u32BF]\\s*" +

              // 6) 方括号中文/关键字：【一】【摘要】【引言】【结论】【附录】【参考文献】
              "|[【\\[][^】\\]]{1,12}[】\\]]\\s*" +

              // 7) 大写字母（A~Z）+ 点/顿号：A.  B、  C．（翻译文献/选择题常用）
              "|[A-Z][、.．]\\s*" +

              // 8) Markdown 标题（兼容导出的 txt / 直接导入 md）
              "|#{1,6}\\s+" +

              // 9) 附录/附表/附图/补充说明：附录A / 附表 3 / 附图二
              "|(?:附录|附表|附图|附页|补充|附件)[\\s一二三四五六七八九十百千零〇两A-Za-z0-9.]*\\s*" +
            ")" +
            ".+$"
    );

    /** 单个章节块超过此字符数时，触发 recursive 兜底切分（中文≈ 1char ≈ 0.8 token） */
    private static final int MAX_CHARS_PER_SEGMENT = 600;

    /** 兜底切分时的 overlap，避免跨 chunk 丢失句首术语和上下文 */
    private static final int OVERLAP_CHARS = 80;

    /** 标题内容最小长度（去掉编号前缀后的正文文字数），避免 "1." "## " 等被误判 */
    private static final int MIN_HEADER_TEXT_LEN = 2;

    /** 内部兜底分片器：递归按 段落→句子→字符 降级切分 */
    private final DocumentSplitter fallbackSplitter = DocumentSplitters.recursive(
            MAX_CHARS_PER_SEGMENT, OVERLAP_CHARS
    );

    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> result = new ArrayList<>();

        String sourceFile = extractFileName(document);
        String fullText = document.text();
        if (fullText == null || fullText.isBlank()) {
            return result;
        }

        String[] lines = fullText.split("\\r?\\n");

        StringBuilder currentSection = new StringBuilder();
        String currentTitle = "(文档开头)";
        int sectionCount = 0;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();

            if (isSectionHeader(line)) {
                // 识别到标题：先 flush 前一章节，再开启新章节
                if (currentSection.length() > 0) {
                    flushSection(currentSection.toString(), currentTitle,
                            sourceFile, document, result);
                    sectionCount++;
                    currentSection.setLength(0);
                }
                currentTitle = line;
                currentSection.append(line).append("\n");
            } else {
                currentSection.append(rawLine).append("\n");
            }
        }

        // 收尾 flush
        if (currentSection.length() > 0) {
            flushSection(currentSection.toString(), currentTitle,
                    sourceFile, document, result);
            sectionCount++;
        }

        if (sectionCount <= 1) {
            log.debug("[分片器] 文件[{}]未识别到明显章节标题，走兜底递归切分", sourceFile);
        } else {
            log.info("[分片器] 文件[{}] 按章节拆为 {} 块，超长块将继续递归细分",
                    sourceFile, sectionCount);
        }

        return result;
    }

    /**
     * 输出一个章节块：
     * - 字符数 <= 阈值 → 直接产出一个 TextSegment；
     * - 字符数 > 阈值 → 用内置 recursive splitter 兜底切，子段继承父 section_title/file_name。
     */
    private void flushSection(String sectionText, String sectionTitle,
                              String sourceFile, Document sourceDoc,
                              List<TextSegment> collector) {

        String trimmed = sectionText.trim();
        if (trimmed.isEmpty()) return;

        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_CHARS_PER_SEGMENT) {
            collector.add(buildSegment(trimmed, sectionTitle, sourceFile, sourceDoc));
            return;
        }

        // 超长章节：包成临时 Document 交给 LangChain4j 内置 recursive splitter 兜底
        Document sectionDoc = Document.from(trimmed, sourceDoc.metadata());
        List<TextSegment> subs = fallbackSplitter.split(sectionDoc);

        for (TextSegment sub : subs) {
            collector.add(buildSegment(sub.text(), sectionTitle, sourceFile, sourceDoc));
        }
    }

    /** 构造 TextSegment，附加 file_name / section_title metadata */
    private TextSegment buildSegment(String text, String sectionTitle,
                                     String sourceFile, Document sourceDoc) {
        dev.langchain4j.data.document.Metadata metadata = sourceDoc.metadata().copy();
        metadata.put("file_name", sourceFile);
        metadata.put("section_title", sectionTitle);
        return TextSegment.from(text, metadata);
    }

    // ==================== 标题识别辅助 ====================

    /**
     * 判断一行是否是章节标题：正则匹配 + 内容最小长度双重校验，减少误判
     */
    private boolean isSectionHeader(String line) {
        if (line == null || line.isBlank()) return false;
        if (!SECTION_HEADER.matcher(line).matches()) return false;

        int idx = indexOfFirstTitleChar(line);
        int textLen = line.length() - idx;
        return textLen >= MIN_HEADER_TEXT_LEN;
    }

    /**
     * 跳过编号前缀，找到第一个真正的"标题正文"字符位置，
     * 用于判断去除编号后剩余内容长度，避免空编号行误判。
     */
    private static int indexOfFirstTitleChar(String line) {
        int i = 0;
        int len = line.length();
        while (i < len && Character.isWhitespace(line.charAt(i))) i++;
        if (i >= len) return i;
        char c = line.charAt(i);

        // 分支1：第X章/节/条/...
        if (c == '第') {
            i++;
            while (i < len && !isChapterUnitChar(line.charAt(i))) i++;
            if (i < len) i++;
        }

        // 分支2：【关键词】 / [关键词] / (编号) /（编号）
        else if (c == '【' || c == '[' || c == '(' || c == '（') {
            char close = (c == '【') ? '】' : (c == '[') ? ']' : (c == '(') ? ')' : '）';
            while (i < len && line.charAt(i) != close) i++;
            if (i < len) i++;
        }

        // 分支3：圆圈数字（Unicode 单字）
        else if (isCircledNumber(c)) {
            i++;
        }

        // 分支4：中文大写数字 + 顿号/点
        else if (isChineseNumeral(c)) {
            while (i < len && isChineseNumeral(line.charAt(i))) i++;
            if (i < len && isPunctuationSeparator(line.charAt(i))) i++;
        }

        // 分支5：附录 / 附表 / 附图 / 附件 / 补充 + 编号
        else if (startsWithAppendixKeyword(line, i)) {
            i = skipAppendixKeyword(line, i);
        }

        // 分支6：数字 (0-9) / 英文大写字母 (A-Z) / 井号 (#)
        else if (Character.isDigit(c) || Character.isUpperCase(c) || c == '#') {
            while (i < len &&
                    (Character.isDigit(line.charAt(i)) || Character.isUpperCase(line.charAt(i))
                            || line.charAt(i) == '.' || line.charAt(i) == '、' || line.charAt(i) == '．'
                            || line.charAt(i) == '#' || Character.isWhitespace(line.charAt(i)))) {
                i++;
            }
        }

        // 跳过尾部空白，返回正文首字符位置
        while (i < len && Character.isWhitespace(line.charAt(i))) i++;
        return i;
    }

    private static boolean isChapterUnitChar(char c) {
        return c == '章' || c == '节' || c == '课' || c == '条' || c == '部' || c == '分'
                || c == '篇' || c == '卷' || c == '编' || c == '步' || c == '讲' || c == '集'
                || c == '回' || c == '道' || c == '单' || c == '元' || c == '季'
                || c == '度' || c == '册' || c == '期';
    }

    private static boolean isChineseNumeral(char c) {
        return c == '一' || c == '二' || c == '三' || c == '四' || c == '五'
                || c == '六' || c == '七' || c == '八' || c == '九' || c == '十'
                || c == '百' || c == '千' || c == '零' || c == '〇' || c == '两';
    }

    private static boolean isCircledNumber(char c) {
        return (c >= '\u2460' && c <= '\u2473')
                || (c >= '\u3251' && c <= '\u325F')
                || (c >= '\u32B1' && c <= '\u32BF');
    }

    private static boolean isPunctuationSeparator(char c) {
        return c == '、' || c == '.' || c == '．';
    }

    private static boolean startsWithAppendixKeyword(String line, int start) {
        if (start + 1 >= line.length()) return false;
        String prefix = line.substring(start, Math.min(start + 4, line.length()));
        return prefix.startsWith("附录") || prefix.startsWith("附表")
                || prefix.startsWith("附图") || prefix.startsWith("附件")
                || prefix.startsWith("附页") || prefix.startsWith("补充");
    }

    private static int skipAppendixKeyword(String line, int start) {
        int i = start;
        int len = line.length();
        if (i + 2 <= len) i += 2;
        if (i + 2 <= len) {
            String extra = line.substring(i, i + 2);
            if ("说明".equals(extra) || "材料".equals(extra) || "清单".equals(extra)
                    || "数据".equals(extra) || "信息".equals(extra)) {
                i += 2;
            }
        }
        while (i < len && (Character.isWhitespace(line.charAt(i))
                || Character.isDigit(line.charAt(i))
                || Character.isUpperCase(line.charAt(i))
                || isChineseNumeral(line.charAt(i))
                || line.charAt(i) == '.' || line.charAt(i) == '、' || line.charAt(i) == '．')) {
            i++;
        }
        return i;
    }

    private static String extractFileName(Document doc) {
        try {
            String name = doc.metadata().getString("file_name");
            if (name != null && !name.isBlank()) return name;
        } catch (Exception ignored) {
        }
        try {
            String src = doc.metadata().getString("source");
            if (src != null && !src.isBlank()) {
                int sep = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
                return sep >= 0 ? src.substring(sep + 1) : src;
            }
        } catch (Exception ignored) {
        }
        try {
            String abs = doc.metadata().getString("absolute_path");
            if (abs != null && !abs.isBlank()) {
                int sep = Math.max(abs.lastIndexOf('/'), abs.lastIndexOf('\\'));
                return sep >= 0 ? abs.substring(sep + 1) : abs;
            }
        } catch (Exception ignored) {
        }
        return "(unknown)";
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        List<TextSegment> all = new ArrayList<>();
        for (Document doc : documents) {
            all.addAll(split(doc));
        }
        return all;
    }
}
