package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TxtChapterSplitterTest {

    private TxtChapterSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new TxtChapterSplitter();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ChapterSplitConfigEntity config(String pattern, int titleGroup, boolean includeMatch) {
        ChapterSplitConfigEntity c = new ChapterSplitConfigEntity();
        c.setPattern(pattern);
        c.setTitleGroup(titleGroup);
        c.setIncludeMatch(includeMatch);
        return c;
    }

    // ── split() ───────────────────────────────────────────────────────────────

    @Test
    void split_nullText_returnsEmpty() {
        assertThat(splitter.split(null, List.of())).isEmpty();
    }

    @Test
    void split_blankText_returnsEmpty() {
        assertThat(splitter.split("   ", List.of())).isEmpty();
    }

    @Test
    void split_noConfigMatches_returnsSingleChapter() {
        // pattern that will never match
        ChapterSplitConfigEntity cfg = config("NOMATCH_\\d+", 0, false);
        List<TxtChapterSplitter.SplitChapter> result = splitter.split("全文内容", List.of(cfg));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("全文");
        assertThat(result.get(0).number()).isEqualTo(1);
    }

    @Test
    void split_usesFirstMatchingConfig() {
        ChapterSplitConfigEntity noMatch = config("NOMATCH_\\d+", 0, false);
        ChapterSplitConfigEntity matches = config("第[一二三四五六七八九十]+章", -1, false);

        String text = "第一章\n内容A\n第二章\n内容B";
        List<TxtChapterSplitter.SplitChapter> result = splitter.split(text, List.of(noMatch, matches));
        assertThat(result).hasSize(2);
    }

    @Test
    void split_emptyConfigList_returnsSingleChapter() {
        List<TxtChapterSplitter.SplitChapter> result = splitter.split("全文内容", List.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("全文");
    }

    // ── splitWithConfig() ─────────────────────────────────────────────────────

    @Test
    void splitWithConfig_chapterPattern_extractsTitleAndContent() {
        String text = "第一章 序章\n这是序章的内容。\n第二章 开端\n这是第二章的内容。";
        // titleGroup=0 → full match; pattern matches "第X章 Y" on each line
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章 \\S+", 0, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(2);

        assertThat(chapters.get(0).content()).contains("这是序章的内容");
        assertThat(chapters.get(1).content()).contains("这是第二章的内容");
        assertThat(chapters.get(0).number()).isEqualTo(1);
        assertThat(chapters.get(1).number()).isEqualTo(2);
    }

    @Test
    void splitWithConfig_noMatch_returnsEmpty() {
        ChapterSplitConfigEntity cfg = config("第\\d+章", 0, false);
        List<TxtChapterSplitter.SplitChapter> result = splitter.splitWithConfig("没有匹配内容", cfg);
        assertThat(result).isEmpty();
    }

    @Test
    void splitWithConfig_includeMatch_true_headlineIncludedInContent() {
        String text = "第一章\n内容A\n第二章\n内容B";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, true);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).startsWith("第一章");
        assertThat(chapters.get(1).content()).startsWith("第二章");
    }

    @Test
    void splitWithConfig_includeMatch_false_headlineExcludedFromContent() {
        String text = "第一章\n内容A\n第二章\n内容B";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).doesNotStartWith("第一章");
        assertThat(chapters.get(0).content()).isEqualTo("内容A");
    }

    @Test
    void splitWithConfig_titleGroupNegative_autoNumbers() {
        String text = "第一章\n内容A\n第二章\n内容B\n第三章\n内容C";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).isEqualTo("第1章");
        assertThat(chapters.get(1).title()).isEqualTo("第2章");
        assertThat(chapters.get(2).title()).isEqualTo("第3章");
    }

    @Test
    void splitWithConfig_titleGroupZero_usesFullMatch() {
        String text = "Chapter 1\n内容A\nChapter 2\n内容B";
        ChapterSplitConfigEntity cfg = config("Chapter \\d+", 0, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).isEqualTo("Chapter 1");
        assertThat(chapters.get(1).title()).isEqualTo("Chapter 2");
    }

    @Test
    void splitWithConfig_lastChapter_reachesEndOfText() {
        String text = "第一章\n内容A\n第二章\n内容B，一直到结尾";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters.get(1).content()).isEqualTo("内容B，一直到结尾");
    }

    @Test
    void splitWithConfig_wordCountEqualsContentLength() {
        String text = "第一章\n内容ABCDE";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).wordCount()).isEqualTo(chapters.get(0).content().length());
    }

    @Test
    void splitWithConfig_emptyContentBetweenMatches_skipped() {
        // Two consecutive patterns with no content between them
        String text = "第一章\n第二章\n内容B";
        ChapterSplitConfigEntity cfg = config("第[一二三四五六七八九十]+章", -1, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        // First match yields empty content → skipped
        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).content()).isEqualTo("内容B");
    }

    @Test
    void splitWithConfig_chineseNumberedChapters() {
        String text = """
                第一百零三章 风云突变
                这章内容很精彩。
                第一百零四章 决战时刻
                高潮部分。
                """;
        ChapterSplitConfigEntity cfg = config("第[零一二三四五六七八九十百千万]+章\\s*\\S*", 0, false);

        List<TxtChapterSplitter.SplitChapter> chapters = splitter.splitWithConfig(text, cfg);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).contains("这章内容很精彩");
        assertThat(chapters.get(1).content()).contains("高潮部分");
    }
}
