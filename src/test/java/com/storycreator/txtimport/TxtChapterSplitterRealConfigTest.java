package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用「生产环境的 YAML 分割配置」跑真实样例文本的回归测试。
 *
 * <p>与 {@link TxtChapterSplitterTest} 的区别：后者使用测试内自造的 pattern，本类直接读取
 * {@code classpath:chapter-split-configs/*.yml}（即 {@link ChapterSplitConfigBuiltinLoader}
 * 实际加载的那批配置），按 YAML 的 {@code order} 组装调度器并还原线上的 sortOrder 顺序，
 * 因此一次同时守住「正则本身」与「优先级」两件事。</p>
 *
 * <p>回归背景（2026-09）：样例文本顶部有一条「-------」分隔线，而「分隔线」配置当时排在
 * 「中文数字章节号」之前，于是它命中了那条分隔线、只切出 1 章就直接胜出，把整本书（含 6 个
 * 「第X章」）合并成单一章节。</p>
 */
class TxtChapterSplitterRealConfigTest {

    /** 不依赖数据库的 Handler：直接使用 YAML 给定的 definition。 */
    static class YamlHandler extends AbstractRegexChapterHandler {
        YamlHandler(String name, String pattern, int titleGroup, boolean includeMatch) {
            super(null, name, pattern, titleGroup, includeMatch);
        }

        @Override
        protected ChapterSplitConfigEntity getConfig() {
            return null; // 不走数据库，使用 YAML 传入的默认 pattern
        }
    }

    private List<ChapterSplitConfigEntity> configs;
    private TxtChapterSplitter splitter;

    @BeforeEach
    void loadYamlConfigs() throws Exception {
        record Def(String name, String pattern, int titleGroup, boolean includeMatch, int order) {
        }

        List<Def> defs = new ArrayList<>();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:chapter-split-configs/*.yml");
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> data = new Yaml().load(is);
                if (data == null || data.get("name") == null) {
                    continue;
                }
                defs.add(new Def(
                        (String) data.get("name"),
                        (String) data.get("pattern"),
                        data.get("titleGroup") != null ? ((Number) data.get("titleGroup")).intValue() : 0,
                        Boolean.TRUE.equals(data.get("includeMatch")),
                        data.get("order") != null ? ((Number) data.get("order")).intValue() : 999));
            }
        }
        defs.sort(Comparator.comparingInt(Def::order));
        assertThat(defs).as("内置分割配置不应为空").hasSizeGreaterThanOrEqualTo(5);
        assertThat(defs).as("每个内置配置都应声明 order（否则优先级会随扫描顺序漂移）")
                .allSatisfy(d -> assertThat(d.order()).isLessThan(999));

        List<ChapterSplitHandler> handlers = new ArrayList<>();
        configs = new ArrayList<>();
        for (Def d : defs) {
            handlers.add(new YamlHandler(d.name(), d.pattern(), d.titleGroup(), d.includeMatch()));
            ChapterSplitConfigEntity cfg = new ChapterSplitConfigEntity();
            cfg.setName(d.name());
            cfg.setPattern(d.pattern());
            cfg.setTitleGroup(d.titleGroup());
            cfg.setIncludeMatch(d.includeMatch());
            cfg.setBuiltin(true);
            cfg.setEnabled(true);
            cfg.setSortOrder(d.order());
            configs.add(cfg);
        }
        splitter = new TxtChapterSplitter(handlers, null, new GenericConfigSplitHandler());
    }

    /** 用户反馈的真实样例：顶部一条分隔线 + 书名/作者/标签头部块 + 6 个「第X章」。 */
    private static final String SAMPLE = String.join("\n",
            "---------------------------------------",
            "《和青梅做了十年朋友后》",
            "作者：晨曦之主",
            "[1-6章]",
            "[未完结]",
            "标签：纯爱、恋爱、青梅",
            "第一章 关于我的青梅给我推荐新朋友这回事",
            "昏暗的房间里，窗帘只拉了一半，傍晚的天光透进来，把空气染成一种暧昧的灰蓝色。",
            "第二章 大小姐的心思",
            "我想快点丢掉处女这个身份，这个念头变得无比清晰、甚至带着点焦虑地扎根在脑海里。",
            "第三章 我的震惊",
            "事情怎么会变成这样——。",
            "第四章 不甘的校花",
            "陈远航的手指，",
            "第五章 校园内的激情",
            "你看嘛，大家都在。",
            "从紧急逃生楼梯那堵粗糙的水泥矮墙后，",
            "第六章 闺蜜的小心思",
            "周五放学铃响过，教室里闹哄哄的，桌椅碰撞声、谈笑声混成一片。");

    @Test
    void sampleWithLeadingSeparator_splitsIntoSixChapters() {
        List<SplitChapter> chapters = splitter.split(SAMPLE, configs);

        assertThat(chapters).as("顶部一条分隔线不得把整本书合并成一章").hasSize(6);
        assertThat(chapters).extracting(SplitChapter::title).containsExactly(
                "关于我的青梅给我推荐新朋友这回事",
                "大小姐的心思",
                "我的震惊",
                "不甘的校花",
                "校园内的激情",
                "闺蜜的小心思");
        // 命中行（标题行）进入标题、不进正文
        assertThat(chapters.get(0).content())
                .contains("昏暗的房间里")
                .doesNotContain("第一章");
        // 头部块（分隔线 / 书名 / 作者 / 标签）不属于任何一章
        assertThat(chapters.get(0).content()).doesNotContain("和青梅做了十年朋友后");
    }

    @Test
    void builtinPriority_putsExactChapterPatternsBeforeHeuristics() {
        assertThat(configs).extracting(ChapterSplitConfigEntity::getName)
                .startsWith("卷章格式", "阿拉伯数字章节号", "中文数字章节号");

        int precise = indexOf("中文数字章节号");
        assertThat(indexOf("独立标题行")).as("启发式不得排在精确章节号之前").isGreaterThan(precise);
        assertThat(indexOf("分隔线")).as("启发式不得排在精确章节号之前").isGreaterThan(precise);
    }

    /** 全角空格（U+3000），中文 TXT 里最常用的标题缩进字符。 */
    private static final String FW = "　";

    /**
     * 用户反馈的真实样例（2026-09）：行首可能是半角空格、全角空格或多个全角空格，
     * 「第N章」与标题之间可能有空格也可能直接相连；同时还存在只有截图的极短正文。
     */
    private static final String ARABIC_MIXED_SPACE_SAMPLE = String.join("\n",
            "第1章二百块，玩一天(加料)",
            "\"哥，崩根烟抽呗？\"",
            FW + "第2章 今晚你怎么样都可以(加料)",
            "\"哥。\"",
            "黄毛的声音突然贴着耳朵响起来。",
            FW + "第3章 六个脑袋同时低下(加料)",
            "\"哥，还有烟不？\"",
            "\"吃饭、喝茶、住宿，还有优先预订权。以后你们想自己来，不用等我。\"",
            FW + FW + "第414章 挑房间(加料)",
            "白晓静站在原地，",
            FW + "第415章一起玩游戏(加料)",
            "林野刚把手机放下，");

    @Test
    void arabicHeadingsWithFullWidthSpaces_areAllSplit() {
        List<SplitChapter> chapters = splitter.split(ARABIC_MIXED_SPACE_SAMPLE, configs);

        assertThat(chapters).as("行首的全角空格必须在匹配前归一化为半角空格，否则整章漏切").hasSize(5);
        assertThat(chapters).extracting(SplitChapter::title).containsExactly(
                "二百块，玩一天(加料)",
                "今晚你怎么样都可以(加料)",
                "六个脑袋同时低下(加料)",
                "挑房间(加料)",
                "一起玩游戏(加料)");
        // 标题行本身不进正文
        assertThat(chapters.get(0).content()).doesNotContain("第1章");
    }

    /** 标题行自「第」起到行尾超过 50 字符的，视为正文而非标题。 */
    private static final String LONG_LINE_SAMPLE = String.join("\n",
            "第一章 开端",
            "他站在雨里。",
            "第5章而且这一整段其实是正文，只是碰巧以「第5章」开头，" + "写了很多很多很多很多很多很多很多很多很多很多很多很多的内容",
            "第二章 后续",
            "雨停了。");

    @Test
    void longLineStartingWithChapterNumber_isNotHeading() {
        List<SplitChapter> chapters = splitter.split(LONG_LINE_SAMPLE, configs);

        assertThat(chapters).as("超长行不得被当成章节标题").hasSize(2);
        assertThat(chapters).extracting(SplitChapter::title).containsExactly("开端", "后续");
        assertThat(chapters.get(0).content()).as("超长行应留在上一章正文里").contains("第5章而且这一整段");
    }

    @Test
    void whenNoShortHeadingExists_relaxedFallbackStillSplits() {
        // 整本书的标题行都很长（标题与正文挤在同一行）：严格模式一条都命中不了，
        // 此时必须回落到「不限行长度」的旧行为，否则整本合并成一章。
        String text = String.join("\n",
                "第1章标题与正文挤在一行" + "这是一段很长的正文内容".repeat(4),
                "这里是第一章剩下的段落。",
                "第2章标题与正文也挤在一行" + "这是另一段很长的正文内容".repeat(4),
                "这里是第二章剩下的段落。");

        List<SplitChapter> chapters = splitter.split(text, configs);

        assertThat(chapters).as("严格模式无解时应放宽重试，不能整本合成一章").hasSize(2);
    }

    private int indexOf(String name) {
        for (int i = 0; i < configs.size(); i++) {
            if (name.equals(configs.get(i).getName())) {
                return i;
            }
        }
        throw new AssertionError("未找到配置: " + name);
    }
}
