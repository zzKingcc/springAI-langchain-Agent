package com.zxcSpringAI.processor;

import com.zxcSpringAI.splitter.ChineseArticleDocumentSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 文本类型文档处理策略（.txt .md .markdown .text）
 */
@Slf4j
public class TextDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    /** 适用类型 */
    public static final List<String> TEXT_EXTENSIONS = List.of(
            "txt", "text", "md", "markdown"
    );

    private final ChineseArticleDocumentSplitter splitter = new ChineseArticleDocumentSplitter();

    @Override
    protected List<TextSegment> splitDocuments(List<Document> documents) {
        return splitter.splitAll(documents);
    }

    @Override
    public List<String> supportedExtensions() {
        return TEXT_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "文本类型";
    }

    /**
     * 判断给定扩展名是否为文本类型（工厂调用用）
     */
    public static boolean isTextExtension(String ext) {
        if (ext == null || ext.isBlank()) return false;
        return TEXT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
