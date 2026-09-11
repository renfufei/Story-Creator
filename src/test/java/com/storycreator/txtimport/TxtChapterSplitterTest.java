package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TxtChapterSplitterTest {

    // ── 不依赖数据库的测试 Handler：直接给定 pattern/titleGroup/includeMatch ──
    static class TestHandler extends AbstractRegexChapterHandler {
        TestHandler(String name, String pattern, int titleGroup, boolean includeMatch) {
            super(null, name, pattern, titleGroup, includeMatch);
        }

        @Override
        protected ChapterSplitConfigEntity getConfig() {
            return null; // 不走数据库，使用默认 pattern
        }
    }

    private AbstractRegexChapterHandler cnHandler;
    private TxtChapterSplitter splitter;

    @BeforeEach
    void setUp() {
        cnHandler = new TestHandler("中文数字章节号",
                "(?m)^\\s*第[零一二三四五六七八九十百千万]+章[ \\t　]*(.*)$", 1, false);
        splitter = new TxtChapterSplitter(List.of(cnHandler), null, null);
    }

    // ── 标题清洗（TODO2 第 3 项）：去除首尾中文/半角冒号 ──

    @Test
    void cleanTitle_stripsLeadingChineseColon() {
        assertThat(AbstractRegexChapterHandler.cleanTitle("：开端")).isEqualTo("开端");
    }

    @Test
    void cleanTitle_stripsLeadingColonAfterChapterMarker() {
        // 「第一章：开端」经 titleGroup 提取后标题为「：开端」
        assertThat(AbstractRegexChapterHandler.cleanTitle("：开端")).isEqualTo("开端");
    }

    @Test
    void cleanTitle_stripsHalfWidthColonWithSpaces() {
        assertThat(AbstractRegexChapterHandler.cleanTitle(": 序章")).isEqualTo("序章");
    }

    @Test
    void cleanTitle_stripsTrailingColon() {
        assertThat(AbstractRegexChapterHandler.cleanTitle("序章：")).isEqualTo("序章");
    }

    @Test
    void cleanTitle_keepsInternalColon() {
        assertThat(AbstractRegexChapterHandler.cleanTitle("问与答：开场白")).isEqualTo("问与答：开场白");
    }

    @Test
    void cleanTitle_nullReturnsNull() {
        assertThat(AbstractRegexChapterHandler.cleanTitle(null)).isNull();
    }

    // ── 分割：中文数字章节 + 中文冒号清洗 ──

    @Test
    void split_chineseColonInTitle_isCleaned() {
        String text = "第一章：开端\n这是序章的内容。\n第二章：发展\n这是第二章的内容。";
        List<SplitChapter> chapters = cnHandler.split(text);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).isEqualTo("开端");
        assertThat(chapters.get(1).title()).isEqualTo("发展");
        assertThat(chapters.get(0).content()).contains("这是序章的内容");
        assertThat(chapters.get(1).content()).contains("这是第二章的内容");
    }

    @Test
    void split_chineseNumberedChapters_numberingAutoWhenNoTitle() {
        String text = "第一章\n内容A\n第二章\n内容B\n第三章\n内容C";
        List<SplitChapter> chapters = cnHandler.split(text);
        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).title()).isEqualTo("第1章");
        assertThat(chapters.get(1).title()).isEqualTo("第2章");
        assertThat(chapters.get(2).title()).isEqualTo("第3章");
    }

    @Test
    void split_emptyContentBetweenMatches_skipped() {
        String text = "第一章\n第二章\n内容B";
        List<SplitChapter> chapters = cnHandler.split(text);
        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).content()).isEqualTo("内容B");
    }

    @Test
    void split_includeMatch_false_headlineExcluded() {
        String text = "第一章\n内容A\n第二章\n内容B";
        AbstractRegexChapterHandler h = new TestHandler("x", "(?m)^第[零一二三四五六七八九十]+章", -1, false);
        List<SplitChapter> chapters = h.split(text);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).content()).isEqualTo("内容A");
    }

    @Test
    void canHandle_falseWhenNoMatch() {
        assertThat(cnHandler.canHandle("没有匹配内容")).isFalse();
        assertThat(cnHandler.canHandle("第一章 有匹配")).isTrue();
    }

    // ── 调度器：遍历 Handler，首个 canHandle 命中即分割 ──

    @Test
    void dispatcher_nullText_returnsEmpty() {
        assertThat(splitter.split(null)).isEmpty();
    }

    @Test
    void dispatcher_noMatch_returnsSingleFullTextChapter() {
        AbstractRegexChapterHandler noMatch = new TestHandler("nope", "NOMATCH_\\d+", 0, false);
        ChapterSplitConfigEntity cfg = new ChapterSplitConfigEntity();
        cfg.setName("nope");
        cfg.setEnabled(true);
        TxtChapterSplitter s = new TxtChapterSplitter(List.of(noMatch), null, new GenericConfigSplitHandler());
        List<SplitChapter> result = s.split("全文内容", List.of(cfg));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("全文");
    }

    @Test
    void dispatcher_usesFirstCanHandleHandler() {
        AbstractRegexChapterHandler noMatch = new TestHandler("nope", "NOMATCH_\\d+", 0, false);
        ChapterSplitConfigEntity c1 = new ChapterSplitConfigEntity();
        c1.setName("nope");
        c1.setEnabled(true);
        ChapterSplitConfigEntity c2 = new ChapterSplitConfigEntity();
        c2.setName("中文数字章节号");
        c2.setEnabled(true);
        String text = "第一章\n内容A\n第二章\n内容B";
        List<SplitChapter> result = new TxtChapterSplitter(List.of(noMatch, cnHandler), null, new GenericConfigSplitHandler())
                .split(text, List.of(c1, c2));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("第1章");
    }

    @Test
    void dispatcher_withConfigs_usesOnlyThoseHandlers() {
        // 仅传入 noMatch 配置（无 cnHandler）→ 无命中 → 全文回落
        ChapterSplitConfigEntity cfg = new ChapterSplitConfigEntity();
        cfg.setName("nope");
        cfg.setEnabled(true);
        TxtChapterSplitter s = new TxtChapterSplitter(List.of(cnHandler), null, new GenericConfigSplitHandler());
        List<SplitChapter> result = s.split("第一章 内容", List.of(cfg));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("全文");
    }

    @Test
    void splitRegex_staticUtil_works() {
        List<SplitChapter> chapters = AbstractRegexChapterHandler.splitRegex(
                "第一章：开端\nA\n第二章：发展\nB",
                "(?m)^\\s*第[零一二三四五六七八九十]+章[ \\t　]*(.*)$", 1, false);
        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).isEqualTo("开端");
    }
}
