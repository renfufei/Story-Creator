package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用回落 Handler：用于处理「没有专属 Handler 绑定」的自定义分割配置。
 *
 * 调度器在按配置名称找不到对应专属 Handler 时，会直接用本类按该配置的
 * pattern / titleGroup / includeMatch 进行正则分割，从而兼容用户在界面上自行新增的分割规则，
 * 不会因为重构而丢失自定义分割能力。
 *
 * 注意：本类不实现 {@link ChapterSplitHandler}（它没有固定的 configName），仅作为工具被调度器直接调用。
 */
@Component
public class GenericConfigSplitHandler {

    public List<SplitChapter> split(String text, ChapterSplitConfigEntity config) {
        if (config == null || config.getPattern() == null || config.getPattern().isBlank()) {
            return List.of();
        }
        return AbstractRegexChapterHandler.splitRegex(
                text, config.getPattern(), config.getTitleGroup(), config.isIncludeMatch());
    }
}
