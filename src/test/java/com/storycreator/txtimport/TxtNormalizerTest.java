package com.storycreator.txtimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link TxtNormalizer} 的单元测试：重点守住「替换后长度与下标不变」这个前提。 */
class TxtNormalizerTest {

    @Test
    void replacesWideSpacesWithAsciiSpace() {
        assertThat(TxtNormalizer.normalizeSpaces("　第1章 开端"))
                .isEqualTo(" 第1章 开端");
        assertThat(TxtNormalizer.normalizeSpaces("a b"))
                .isEqualTo("a b");
    }

    @Test
    void preservesLengthAndCharacterIndexes() {
        String raw = "　第1章 开端\n　　第二行内容　结尾";
        String normalized = TxtNormalizer.normalizeSpaces(raw);

        assertThat(normalized).as("归一化必须是 1:1 替换，长度不得变化").hasSameSizeAs(raw);
        for (int i = 0; i < raw.length(); i++) {
            boolean wide = raw.charAt(i) == '　' || raw.charAt(i) == ' ';
            assertThat(normalized.charAt(i))
                    .as("下标 %d 处字符应与原文一一对应（宽空格除外）", i)
                    .isEqualTo(wide ? ' ' : raw.charAt(i));
        }
    }

    @Test
    void leavesCleanTextUntouched() {
        String raw = "第1章 开端\n正文内容";
        assertThat(TxtNormalizer.normalizeSpaces(raw)).isSameAs(raw);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(TxtNormalizer.normalizeSpaces(null)).isNull();
        assertThat(TxtNormalizer.normalizeSpaces("")).isEmpty();
    }

    @Test
    void detectsWideSpaceCharacters() {
        assertThat(TxtNormalizer.isWideSpace('　')).isTrue();
        assertThat(TxtNormalizer.isWideSpace(' ')).isTrue();
        assertThat(TxtNormalizer.isWideSpace(' ')).isFalse();
        assertThat(TxtNormalizer.isWideSpace('第')).isFalse();
    }
}
