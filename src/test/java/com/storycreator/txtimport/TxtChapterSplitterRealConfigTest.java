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

    private int indexOf(String name) {
        for (int i = 0; i < configs.size(); i++) {
            if (name.equals(configs.get(i).getName())) {
                return i;
            }
        }
        throw new AssertionError("未找到配置: " + name);
    }
}
