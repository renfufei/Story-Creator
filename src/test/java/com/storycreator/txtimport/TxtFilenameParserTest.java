package com.storycreator.txtimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TxtFilenameParser} 纯函数单测：覆盖书名号标题、作者标记、回退与边界情形。
 */
class TxtFilenameParserTest {

    @Test
    void parsesTitleAndAuthorFromBookBracketsAndAuthorTag() {
        String name = "《和青梅做了十年朋友后》作者：晨曦之主[1-6章][未完结].txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("和青梅做了十年朋友后");
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("晨曦之主");
    }

    @Test
    void authorStopsAtBrackets() {
        String name = "《书名》作者：张三（著）.txt";
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("张三");
    }

    @Test
    void halfWidthColonAlsoWorks() {
        String name = "《书名》作者:李四.txt";
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("李四");
    }

    @Test
    void titleFallsBackToFilenameWithoutExtensionWhenNoBrackets() {
        String name = "我的小说[完结].txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("我的小说[完结]");
        assertThat(TxtFilenameParser.parseAuthor(name)).isNull();
    }

    @Test
    void noAuthorTagReturnsNull() {
        String name = "《只有书名没有作者》.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("只有书名没有作者");
        assertThat(TxtFilenameParser.parseAuthor(name)).isNull();
    }

    @Test
    void authorInsideTitleBracketsIsNotMistakenForAuthor() {
        // 书名号内的「作者：」不应被当作作者标记
        String name = "《我的作者：故事》.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("我的作者：故事");
        assertThat(TxtFilenameParser.parseAuthor(name)).isNull();
    }

    @Test
    void authorAfterTitleBracketsStillParsed() {
        // 作者标记出现在书名号之后、且前面有其它前缀文本，仍应正确解析
        String name = "《书名》一些前缀作者：王五[完结].txt";
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("王五");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(TxtFilenameParser.parseTitle(null)).isEqualTo("未命名");
        assertThat(TxtFilenameParser.parseTitle("")).isEqualTo("未命名");
        assertThat(TxtFilenameParser.parseAuthor(null)).isNull();
        assertThat(TxtFilenameParser.parseAuthor("")).isNull();
    }

    @Test
    void middleDotAllowedInAuthorName() {
        String name = "《书名》作者：唐家·三少[完结].txt";
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("唐家·三少");
    }

    // ==================== 中文方括号【】标题模式 ====================

    @Test
    void parsesTitleFromSquareBracketsWithAuthorAfter() {
        // 用户实测样例：【标题】 + 章节区间 + 状态 + 作者标记
        String name = "【我的测试人员】 1-147 9人物设定 未完结 作者： 散人.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("我的测试人员");
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("散人");
    }

    @Test
    void squareBracketTitleWithoutAuthorTag() {
        String name = "【测试之书】未完结.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("测试之书");
        assertThat(TxtFilenameParser.parseAuthor(name)).isNull();
    }

    @Test
    void squareBracketTitleWithoutAuthorTagFallbackAuthorNull() {
        // 方括号标题内含「作者：」不应被误判（作者只在】之后搜索）
        String name = "【我的作者：手记】.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("我的作者：手记");
        assertThat(TxtFilenameParser.parseAuthor(name)).isNull();
    }

    @Test
    void bookBracketsTakePriorityOverSquareBrackets() {
        // 两种括号同时出现时，书名号《》优先
        String name = "【前缀】《真正的书名》作者：赵六.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("真正的书名");
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("赵六");
    }

    @Test
    void squareBracketAuthorTagWithoutSpaceAfterColon() {
        String name = "【速记本】作者:陈七.txt";
        assertThat(TxtFilenameParser.parseTitle(name)).isEqualTo("速记本");
        assertThat(TxtFilenameParser.parseAuthor(name)).isEqualTo("陈七");
    }
}
