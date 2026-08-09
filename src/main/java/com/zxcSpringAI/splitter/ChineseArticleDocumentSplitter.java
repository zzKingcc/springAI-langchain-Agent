package com.zxcSpringAI.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文文章文档分片器
 */
public class ChineseArticleDocumentSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChineseArticleDocumentSplitter.class);

    private static final Pattern SECTION_HEADER = Pattern.compile(
            "^\\s*" +
            "(?:" +
              "[一二三四五六七八九十百千零〇两]+[、.．]\\s*" +
              "|第[一二三四五六七八九十百千零〇两0-9]+[章节课条部分篇卷编步讲集回道单元章节季度册期]+\\s*" +
              "|\\d+(?:\\.\\d+)*[、.．]?\\s+" +
              "|[(（][\\d一二三四五六七八九十百千零〇两]+[)）]\\s*" +
              "|[\\u2460-\\u2473\\u3251-\\u325F\\u32B1-\\u32BF]\\s*" +
              "|[【\\[][^】\\]]{1,12}[】\\]]\\s*" +
              "|[A-Z][、.．]\\s*" +
              "|#{1,6}\\s+" +
              "|(?:附录|附表|附图|附页|补充|附件)[\\s一二三四五六七八九十百千零〇两A-Za-z0-9.]*\\s*" +
            ")" +
            ".+$"
    );

    /** 单段最大字符数阈值，超过则触发兜底递归切分 */
    private static final int MAX_CHARS_PER_SEGMENT = 600;

    /** 兜底切分时的子段重叠字符数 */
    private static final int OVERLAP_CHARS = 80;

    /** 去除编号前缀后标题正文的最小长度，用于减少误判 */
    private static final int MIN_HEADER_TEXT_LEN = 2;

    /** 兜底分片器：递归按 段落→句子→字符 降级切分 */
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

    /** 输出章节块：短章节直接产出，超长章节递归细分 */
    private void flushSection(String sectionText, String sectionTitle,
                              String sourceFile, Document sourceDoc,
                              List<TextSegment> collector) {

        String trimmed = sectionText.trim();
        if (trimmed.isEmpty()) return;

        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_CHARS_PER_SEGMENT) {
            collector.add(buildSegment(trimmed, sectionTitle, sourceFile, sourceDoc));
            return;
        }

        Document sectionDoc = Document.from(trimmed, sourceDoc.metadata());
        List<TextSegment> subs = fallbackSplitter.split(sectionDoc);

        for (TextSegment sub : subs) {
            collector.add(buildSegment(sub.text(), sectionTitle, sourceFile, sourceDoc));
        }
    }

    /** 构造 TextSegment，注入 file_name 和 section_title 元数据 */
    private TextSegment buildSegment(String text, String sectionTitle,
                                     String sourceFile, Document sourceDoc) {
        dev.langchain4j.data.document.Metadata metadata = sourceDoc.metadata().copy();
        metadata.put("file_name", sourceFile);
        metadata.put("section_title", sectionTitle);
        return TextSegment.from(text, metadata);
    }

    /** 判断是否章节标题：正则匹配 + 正文长度双重校验 */
    private boolean isSectionHeader(String line) {
        if (line == null || line.isBlank()) return false;
        if (!SECTION_HEADER.matcher(line).matches()) return false;

        int idx = indexOfFirstTitleChar(line);
        int textLen = line.length() - idx;
        return textLen >= MIN_HEADER_TEXT_LEN;
    }

    /** 跳过编号前缀，定位到标题正文第一个字符位置 */
    private static int indexOfFirstTitleChar(String line) {
        int i = 0;
        int len = line.length();
        while (i < len && Character.isWhitespace(line.charAt(i))) i++;
        if (i >= len) return i;
        char c = line.charAt(i);

        if (c == '第') {
            i++;
            while (i < len && !isChapterUnitChar(line.charAt(i))) i++;
            if (i < len) i++;
        } else if (c == '【' || c == '[' || c == '(' || c == '（') {
            char close = (c == '【') ? '】' : (c == '[') ? ']' : (c == '(') ? ')' : '）';
            while (i < len && line.charAt(i) != close) i++;
            if (i < len) i++;
        } else if (isCircledNumber(c)) {
            i++;
        } else if (isChineseNumeral(c)) {
            while (i < len && isChineseNumeral(line.charAt(i))) i++;
            if (i < len && isPunctuationSeparator(line.charAt(i))) i++;
        } else if (startsWithAppendixKeyword(line, i)) {
            i = skipAppendixKeyword(line, i);
        } else if (Character.isDigit(c) || Character.isUpperCase(c) || c == '#') {
            while (i < len &&
                    (Character.isDigit(line.charAt(i)) || Character.isUpperCase(line.charAt(i))
                            || line.charAt(i) == '.' || line.charAt(i) == '、' || line.charAt(i) == '．'
                            || line.charAt(i) == '#' || Character.isWhitespace(line.charAt(i)))) {
                i++;
            }
        }

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

    /** 从 Document metadata 中提取来源文件名 */
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
