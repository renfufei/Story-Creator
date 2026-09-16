package com.storycreator.web;

import com.storycreator.testsupport.BrowserSmokeSupport;
import com.storycreator.testsupport.HeadlessChrome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灵感模块的浏览器冒烟测试——在 {@link BrowserSmokeSupport} 的四类信号校验之上，
 * 额外断言各页由 JS 回填的<b>具体内容</b>。
 *
 * <p>全站页面的基础冒烟见 {@link BrowserSmokePagesTest}。
 */
@Tag("browser")
class BrowserSmokeTest extends BrowserSmokeSupport {

    @Test
    void allInspirationsPage_rendersGroupedByProject() {
        smoke("/inspirations", "#groups .card", "所有灵感", true, page -> {
            assertThat(page.count("#groups .card"))
                    .as("跨项目汇总页应至少渲染出一个项目分组")
                    .isGreaterThanOrEqualTo(1);
            assertThat(page.evaluate("[...document.querySelectorAll('#groups a')]"
                    + ".some(a => a.getAttribute('href') === '/projects/" + projectId + "/inspirations')"))
                    .as("分组卡片应包含指向该项目灵感列表的深链接")
                    .isEqualTo("true");
        });
    }

    @Test
    void projectInspirationListPage_rendersItems() {
        smoke("/projects/" + projectId + "/inspirations", "#list .card", "新建灵感", true, page -> {
            assertThat(page.count("#list .card"))
                    .as("项目灵感列表应渲染出 2 条灵感")
                    .isEqualTo(2);
            assertThat(page.evaluate("document.getElementById('count').textContent"))
                    .as("列表计数应由 JS 回填为 2")
                    .isEqualTo("2");
        });
    }

    @Test
    void inspirationDetailPage_rendersContent() {
        smoke("/projects/" + projectId + "/inspirations/" + inspirationId, "#edit-link[href$='/edit']", "灵感列表",
                true, page -> {
                    assertThat(page.evaluate("document.getElementById('title').textContent"))
                            .as("详情页标题应由 JS 回填")
                            .contains("顿悟");
                    assertThat(page.evaluate("document.getElementById('content-body').textContent.trim().length > 0"))
                            .as("详情页正文应由 JS 回填")
                            .isEqualTo("true");
                });
    }

    @Test
    void inspirationEditPage_rendersForm() {
        smoke("/projects/" + projectId + "/inspirations/" + inspirationId + "/edit", "#edit-form #title", "保存修改",
                true, page -> {
                    assertThat(page.evaluate("document.getElementById('title').value"))
                            .as("编辑表单应回填已有标题")
                            .contains("顿悟");
                });
    }

    /**
     * 自校验：确认采集器真的能抓到运行时 JS 错误。
     *
     * <p>没有这条用例，前面所有断言都可能因为"信号压根没采集到"而恒绿——那就等于没测。
     */
    @Test
    void harness_detectsUncaughtJsErrors() {
        try (HeadlessChrome.Page page = openPage()) {
            page.navigate("http://127.0.0.1:" + port + "/inspirations");
            page.waitForSelector("#site-nav a.navbar-brand", RENDER_TIMEOUT);
            page.evaluate("setTimeout(function () { throw new Error('boom-sentinel'); }, 0)");
            settle(1500);
            assertThat(page.jsErrors())
                    .as("采集器应捕获未处理的运行时错误（否则本套件的 JS 错误断言形同虚设）")
                    .anyMatch(e -> e.contains("boom-sentinel"));
        }
    }
}
