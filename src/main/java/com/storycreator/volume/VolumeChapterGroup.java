package com.storycreator.volume;

import java.util.List;

/**
 * 一个分卷及其实际包含的章节号（按卷号升序排列后的结果项）。
 *
 * @param volumeId        分卷主键（volume_outlines.id），可能为 null（理论上不会，防御用）
 * @param volumeNumber    卷号
 * @param title           卷标题
 * @param arcName         弧名
 * @param chapterNumbers  该卷实际包含的章节号（升序）
 */
public record VolumeChapterGroup(
        Long volumeId,
        int volumeNumber,
        String title,
        String arcName,
        List<Integer> chapterNumbers
) {
}
