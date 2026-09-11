package com.storycreator.txtimport.handler;

import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.txtimport.AbstractRegexChapterHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 分隔线：匹配由 3 个以上相同符号组成的分隔线（===、---、***、——— 等）。
 * 标题采用自动编号（titleGroup < 0）。
 */
@Component
@Order(50)
public class SeparatorLineHandler extends AbstractRegexChapterHandler {

    public SeparatorLineHandler(ChapterSplitConfigRepository configRepository) {
        super(configRepository,
                "分隔线",
                "(?m)^\\s*([=\\-*—]{3,})\\s*$",
                -1,
                false);
    }
}
