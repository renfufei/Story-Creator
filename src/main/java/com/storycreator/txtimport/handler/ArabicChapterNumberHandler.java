package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 阿拉伯数字章节号：匹配「第N章」格式，N 为阿拉伯数字（1、23、123 等）。
 *
 * <p>前置处理：进入分割前已由 {@link com.storycreator.txtimport.TxtNormalizer} 把全角空格
 * 归一化为半角空格，因此开头用的是 {@code \s*} 而非 {@code [ \t　]*}。</p>
 *
 * <p>{@link AbstractRegexChapterHandler#HEADING_LINE_LIMIT_GUARD} 限定「第N章+标题」整行
 * ≤ {@link AbstractRegexChapterHandler#HEADING_LINE_MAX_CHARS} 字符，长段正文不会被误判成标题。</p>
 */
@Component
@Order(20)
public class ArabicChapterNumberHandler extends AbstractRegexChapterHandler {

    public ArabicChapterNumberHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "阿拉伯数字章节号",
                "(?m)^\\s*" + HEADING_LINE_LIMIT_GUARD + "第\\s*\\d+\\s*章[ \\t　]*(.*)$",
                1,
                false);
    }
}
