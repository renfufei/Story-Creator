package com.storycreator.web;

import com.storycreator.testsupport.BrowserSmokeSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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
                new PageCase("/learn", null, "教学模块", true),
                new PageCase("/learn/multiplication", null, "九九乘法口诀", true),
                new PageCase("/learn/multiplication/settings", "#voiceList", "音频管理", true),

                // —— 设置类 ——
                new PageCase("/prompts", "#templateTbody tr", "Prompt模板管理", true),
                new PageCase("/prompts/explore", null, "提示词探索", true),
                new PageCase("/settings/materials", null, "素材列表", true),
                new PageCase("/settings/guidances", "#listArea", "指导列表", true),
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
}
