package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 独立标题行：匹配前后有空行的短行（≤20 字）作为章节标题。
 */
@Component
@Order(40)
public class StandaloneTitleHandler extends AbstractRegexChapterHandler {

    public StandaloneTitleHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "独立标题行",
                "(?m)(?<=\\n\\n|\\A)(.{1,20})(?=\\n\\n)",
                1,
                false);
    }
}
