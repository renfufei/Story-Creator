package com.storycreator.web;

import com.storycreator.testsupport.BrowserSmokeSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全站静态页的浏览器冒烟测试（数据驱动）。
 *
 * <p>每个页面在真实 Chrome 里打开，断言四类信号干净：
 * <ul>
 *   <li>无未捕获 JS 错误；</li>
 *   <li>无 console.error；</li>
 *   <li>无资源加载失败（favicon 除外）；</li>
 *   <li>无外部 CDN 请求（静态资源必须本地化到 /vendor/）。</li>
 * </ul>
 * 并要求导航栏已由 nav.js 挂载（沉浸式全屏页除外，见 {@link PageCase#requireNav}）。
 *
 * <p><b>expectText 是断言 JS 真的跑完的关键</b>：只等 DOM 元素会命中写死在 HTML 里的静态骨架，
 * 那样页面即使脚本崩了也照样"有元素"。故每个用例都给出一段只能由脚本渲染出来的文案。
 * 注意别挑与顶部导航栏重复的文案（下拉菜单文本也在 innerText 里），否则断言恒真。
 */
@Tag("browser")
@DisplayName("全站静态页冒烟")
class BrowserSmokePagesTest extends BrowserSmokeSupport {

    /** @param path 页面路径，{@code {pid}} 会在运行时替换为真实项目 id */
    record PageCase(String path, String sentinel, String expectText, boolean requireNav) {
        @Override
        public String toString() {
            return path;
        }
    }

    static Stream<PageCase> pages() {
        return Stream.of(
                // —— 全局 ——
                new PageCase("/", "#projectContainer", "我的创作项目", true),
                new PageCase("/settings", "#main", "全局默认模型", true),
                new PageCase("/chat", null, "新建会话", true),

                // —— 教学 ——
                // 哨兵挑 .sc-learn-icon：三张卡共用的图标色块，静态 HTML 里就有、且是卡片墙的标志物
                new PageCase("/learn", ".sc-learn-icon", "教学模块", true),
                new PageCase("/learn/multiplication", null, "九九乘法口诀", true),
                new PageCase("/learn/multiplication/settings", "#voiceList", "音频管理", true),
                new PageCase("/learn/word-match", ".wm-card", "选择配对", true),
                // 俄罗斯方块：整页自包含的沉浸式全屏页（body 自带 flex + overflow:hidden，
                // 引 Bootstrap / 挂 #site-nav 都会破版）⇒ requireNav=false。
                // 哨兵挑 .piece-hitarea：它由 init→newGame→dealPieces→renderPieces 动态生成，
                // HTML 骨架里一个都没有，所以"等到它"就等于"脚本真的跑完了"。
                // 页面上没有 JS 才渲染出的**文字**（分数/最高分静态就是 0），expectText 只能留空。
                new PageCase("/learn/block-blast", ".piece-hitarea", null, false),

                // —— 设置类 ——
                new PageCase("/prompts", "#templateTbody tr", "Prompt模板管理", true),
                new PageCase("/prompts/explore", null, "提示词探索", true),
                new PageCase("/settings/materials", null, "素材列表", true),
                new PageCase("/settings/guidances", "#listArea", "条创作指导", true),
                new PageCase("/settings/chapter-split-configs", "#configList", "阿拉伯数字章节号", true),
                new PageCase("/settings/tts-templates", "#userArea", "内置模板", true),

                // —— 语音导出 ——
                new PageCase("/tts-export", null, "TTS 语音导出管理", true),

                // —— 导入 ——
                new PageCase("/import", "#main", "选择备份文件", true),
                new PageCase("/import/txt", "input[type=file]", "上传并分割", true),

                // —— 项目 ——
                new PageCase("/projects/new", "#projectForm", "新建创作项目", true),
                new PageCase("/projects/{pid}", "#detailContainer", "工作流进度", true),
                new PageCase("/projects/{pid}/edit", "#projectForm", "编辑项目", true),
                new PageCase("/projects/{pid}/workflow", null, "世界观设定", true),
                new PageCase("/projects/{pid}/volumes/manage", null, "分卷管理", true),
                new PageCase("/projects/{pid}/side-stories", null, "番外篇", true),
                new PageCase("/projects/{pid}/expansion", null, "情节拓展", true),

                // —— 创作透视（inspect 总览/角色为常规页；章节页是全屏侧边栏布局）——
                new PageCase("/projects/{pid}/inspect", null, "创作透视", true),
                new PageCase("/projects/{pid}/inspect/characters", null, "角色透视", true),

                // —— 沉浸式全屏页：侧边栏 position:fixed top:0，加顶部导航会破坏布局，故无导航栏 ——
                new PageCase("/projects/{pid}/read", null, "返回项目", false),
                new PageCase("/projects/{pid}/inspect/chapters/1", null, "章节大纲", false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void pageRendersWithoutClientSideErrors(PageCase c) {
        String path = c.path().replace("{pid}", String.valueOf(projectId));
        smoke(path, c.sentinel(), c.expectText(), c.requireNav(), null);
    }

    /**
     * 俄罗斯方块的专用冒烟。上面的批次用例只能断言「渲染出来了 + 控制台干净」，
     * 这一个额外钉住三件事：棋盘真的铺满 8×8、托盘真的发到 3 个方块、
     * 以及设置菜单里的【返回】真的跳回 /learn（这是迁入本站时唯一新增的交互）。
     */
    @Test
    @DisplayName("俄罗斯方块：棋盘就绪，设置里的【返回】跳回 /learn")
    void blockBlastBoardIsReadyAndBackButtonReturnsToLearn() {
        smoke("/learn/block-blast", ".piece-hitarea", null, false, page -> {
            assertThat(page.count("#board .cell"))
                    .as("棋盘应由脚本铺满 64 格（HTML 骨架里 #board 是空的）")
                    .isEqualTo(64);
            assertThat(page.count("#pieces-tray .piece-hitarea"))
                    .as("托盘应发满 3 个方块")
                    .isEqualTo(3);

            String menu = page.evaluate("(() => {"
                    + "document.getElementById('settings-btn').click();"
                    + "const el = document.getElementById('menu-quit');"
                    + "return document.getElementById('settings-menu').classList.contains('open')"
                    + " + '|' + el.querySelector('.menu-item-label').textContent.trim();"
                    + "})()");
            assertThat(menu)
                    .as("点设置按钮应展开菜单，且里面有【返回】（原版这一项是「退出游戏」）")
                    .isEqualTo("true|返回");

            page.evaluate("document.getElementById('menu-quit').click()");
            assertThat(page.waitFor("location.pathname === '/learn'", Duration.ofSeconds(10)))
                    .as("点【返回】应跳到教学模块首页 /learn")
                    .isTrue();
        });
    }

    /**
     * 教学首页的三张模块卡必须共用一套规格：色块同尺寸同圆角、按钮同款式同高度且底边对齐。
     *
     * <p>这三张卡一度是三种画风（细描边字形 / 实心字形 / 自绘 SVG）、三种按钮色
     * （{@code btn-primary} / {@code btn-success} / {@code btn-warning}）、三种描述行数，
     * 按钮还各自停在不同的高度上。文案层面的断言抓不到这类"看着不整齐"，只能真的量几何。
     */
    @Test
    @DisplayName("教学首页：三张模块卡共用一套规格（色块等高、按钮同款同高贴底）")
    void learnCardWallUsesOneSpec() {
        smoke("/learn", ".sc-learn-icon", "教学模块", true, page -> {
            String spec = page.evaluate("(() => {"
                    + "const cards = Array.from(document.querySelectorAll('main .card'));"
                    + "const box = el => { const r = el.getBoundingClientRect();"
                    + "    return Math.round(r.width) + 'x' + Math.round(r.height); };"
                    + "const chips = cards.map(c => box(c.querySelector('.sc-learn-icon')));"
                    + "const radius = cards.map(c =>"
                    + "    getComputedStyle(c.querySelector('.sc-learn-icon')).borderTopLeftRadius);"
                    + "const btns = cards.map(c => c.querySelector('.sc-learn-actions .btn'));"
                    + "const rects = btns.map(b => b.getBoundingClientRect());"
                    + "const heights = rects.map(r => Math.round(r.height));"
                    + "const widths = rects.map(r => Math.round(r.width));"
                    + "const bottoms = rects.map(r => Math.round(r.bottom));"
                    + "const classes = btns.map(b => b.className.trim());"
                    // 齿轮必须是不占位的浮层：绝对定位 + z-index + 落在卡片右上角
                    + "const gears = cards.map(c => {"
                    + "    const g = c.querySelector('.sc-learn-extra');"
                    + "    if (!g) return 'none';"
                    + "    const gs = getComputedStyle(g);"
                    + "    const gr = g.getBoundingClientRect(), cr = c.getBoundingClientRect();"
                    + "    const inCard = gr.left >= cr.left - 1 && gr.right <= cr.right + 1"
                    + "        && gr.top >= cr.top - 1 && gr.bottom <= cr.bottom + 1;"
                    + "    const topRight = (gr.left + gr.width / 2) > (cr.left + cr.width / 2)"
                    + "        && (gr.top + gr.height / 2) < (cr.top + cr.height / 2);"
                    + "    return gs.position + '@' + gs.zIndex + ':'"
                    + "        + (inCard && topRight ? 'topRight' : 'elsewhere');"
                    + "});"
                    // 说明文字的行宽：数真实 line box，别拿容器高度除行高
                    + "const descs = cards.map(c => {"
                    + "    const el = c.querySelector('.card-text');"
                    + "    const rg = document.createRange();"
                    + "    rg.selectNodeContents(el);"
                    + "    const byLine = new Map();"
                    + "    Array.from(rg.getClientRects()).filter(q => q.width > 0.5 && q.height > 0.5)"
                    + "        .forEach(q => { const k = Math.round(q.top);"
                    + "            byLine.set(k, (byLine.get(k) || 0) + q.width); });"
                    + "    return Array.from(byLine.values()).map(w => Math.round(w)).join('+');"
                    + "});"
                    + "return [cards.length, chips.join(','), radius.join(','),"
                    + "        heights.join(','), bottoms.join(','), classes.join(','),"
                    + "        widths.join(','), gears.join(';'), descs.join(',')].join('|');"
                    + "})()");

            String[] parts = spec.split("\\|", -1);
            assertThat(parts).as("卡片墙读数应齐 9 段：%s", spec).hasSize(9);
            assertThat(parts[0]).as("应有 3 张模块卡").isEqualTo("3");
            assertThat(distinct(parts[1]))
                    .as("三张卡的图标色块应同宽同高（实测 %s）", parts[1]).hasSize(1);
            assertThat(distinct(parts[2]))
                    .as("三张卡的图标色块应同圆角（实测 %s）", parts[2]).hasSize(1);
            assertThat(distinct(parts[3]))
                    .as("三个按钮应同高（实测 %s）", parts[3]).hasSize(1);
            assertThat(distinct(parts[4]))
                    .as("三个按钮应底边对齐、不再一张高一张低（实测 %s）", parts[4]).hasSize(1);
            assertThat(distinct(parts[5]))
                    .as("按钮应同一款式，不再混用 btn-primary/btn-success/btn-warning（实测 %s）", parts[5])
                    .containsExactly("btn");
            assertThat(distinct(parts[6]))
                    .as("三个按钮应同宽 —— 齿轮是脱流浮层，不该把卡1 的按钮挤窄（实测 %s）", parts[6])
                    .hasSize(1);

            String[] gears = parts[7].split(";", -1);
            assertThat(gears).as("三张卡都要报齿轮读数：%s", parts[7]).hasSize(3);
            assertThat(gears[0])
                    .as("卡1 的音频设置齿轮应是绝对定位 + z-index 的浮层、且落在卡片右上角（实测 %s）", gears[0])
                    .matches("absolute@\\d+:topRight");
            assertThat(gears[1]).as("卡2 不该有齿轮（实测 %s）", gears[1]).isEqualTo("none");
            assertThat(gears[2]).as("卡3 不该有齿轮（实测 %s）", gears[2]).isEqualTo("none");

            // 说明文字：恰好两行，且两行长度均衡（text-wrap: balance 挣来的，
            // 默认 auto 会"第一行塞满、第二行只剩两三个字"，那行孤字最显丑）
            String[] descLines = parts[8].split(",", -1);
            assertThat(descLines).as("三张卡都要报说明文字的行宽：%s", parts[8]).hasSize(3);
            for (int i = 0; i < descLines.length; i++) {
                String[] widths = descLines[i].split("\\+", -1);
                assertThat(widths).as("第 %d 张卡的说明应恰好两行（实测 %s）", i + 1, descLines[i])
                        .hasSize(2);
                int first = Integer.parseInt(widths[0]);
                int second = Integer.parseInt(widths[1]);
                assertThat(Math.min(first, second) * 10)
                        .as("第 %d 张卡的两行应长度均衡（短行 ≥ 长行 70%%，实测 %s）", i + 1, descLines[i])
                        .isGreaterThanOrEqualTo(Math.max(first, second) * 7);
            }
        });
    }

    /** 逗号分隔的读数去重（跨列的 x 坐标不可比，所以这里只比尺寸/高度/底边这类绝对量）。 */
    private static Set<String> distinct(String joined) {
        return new LinkedHashSet<>(Arrays.asList(joined.split(",", -1)));
    }
}
