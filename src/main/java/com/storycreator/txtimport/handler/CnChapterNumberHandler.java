package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 中文数字章节号：匹配「第X章」格式，X 为中文数字（一、二、十三、一百二十三等）。
 */
@Component
@Order(30)
public class CnChapterNumberHandler extends AbstractRegexChapterHandler {

    public CnChapterNumberHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "中文数字章节号",
                "(?m)^\\s*" + HEADING_LINE_LIMIT_GUARD + "第[零一二三四五六七八九十百千万]+章[ \\t　]*(.*)$",
                1,
                false);
    }
}
