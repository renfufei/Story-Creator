package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 阿拉伯数字章节号：匹配「第N章」格式，N 为阿拉伯数字（1、23、123 等）。
 */
@Component
@Order(20)
public class ArabicChapterNumberHandler extends AbstractRegexChapterHandler {

    public ArabicChapterNumberHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "阿拉伯数字章节号",
                "(?m)^\\s*第\\s*\\d+\\s*章[ \\t　]*(.*)$",
                1,
                false);
    }
}
