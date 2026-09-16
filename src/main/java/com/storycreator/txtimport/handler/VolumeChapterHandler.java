package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 卷章格式：匹配「第X卷 第Y章」或「卷X 章Y」格式，支持中文与阿拉伯数字。
 * 比单纯的章节号更具体，故优先尝试（@Order 较小）。
 */
@Component
@Order(10)
public class VolumeChapterHandler extends AbstractRegexChapterHandler {

    public VolumeChapterHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "卷章格式",
                "(?m)^\\s*" + HEADING_LINE_LIMIT_GUARD
                        + "(?:第[零一二三四五六七八九十百千万\\d]+卷)?\\s*第[零一二三四五六七八九十百千万\\d]+章[ \\t　]*(.*)$",
                1,
                false);
    }
}
