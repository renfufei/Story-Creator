package com.storycreator.testsupport;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.GlobalSettingEntity;
import com.storycreator.persistence.entity.InspirationEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.GlobalSettingRepository;
import com.storycreator.persistence.repository.InspirationRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 无头浏览器冒烟测试的公共底座：起浏览器、造基础数据、跑"打开页面 → 采集信号 → 断言"。
 *
 * <p><b>为什么需要这一层</b>：HTTP 断言（{@code 200 OK}）抓不到两类线上最常见的问题——
 * <ul>
 *   <li><b>运行时 JS 错误</b>：如 {@code SC.renderNav is not a function}，页面照样 200，
 *       但脚本第一行就崩，后续渲染逻辑全不执行；</li>
 *   <li><b>资源加载失败</b>：如写死的 CDN 链接 404，样式全丢而 HTTP 断言无感。</li>
 * </ul>
 *
 * <p><b>没有浏览器时自动跳过</b>（见 {@link BrowserLocator}），CI 上 {@code mvn test} 依然绿。
 *
 * <p>子类只需声明用例；页面生命周期由 {@link #smoke} 全权掌管。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:browser_smoke_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=none"
})
public abstract class BrowserSmokeSupport {

    protected static final Path ARTIFACT_DIR = Paths.get("target", "browser-tests");
    protected static final Duration RENDER_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    protected int port;

    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected InspirationRepository inspirationRepository;
    @Autowired protected AiModelConfigRepository aiModelConfigRepository;
    @Autowired protected GlobalSettingRepository globalSettingRepository;
    @Autowired protected TransactionTemplate transactionTemplate;

    private static HeadlessChrome chrome;

    protected Long projectId;
    protected Long configId;
    protected Long inspirationId;

    @BeforeAll
    static void startBrowser() {
        assumeTrue(!BrowserLocator.skipRequested(), "STORY_SKIP_BROWSER_TESTS=true，跳过浏览器冒烟测试");

        // 允许直连外部已运行的浏览器：沙箱 / CI 里测试进程可能无权自行拉起 Chrome
        String cdp = System.getenv(BrowserLocator.ENV_CDP_URL);
        if (cdp != null && !cdp.isBlank()) {
            try {
                chrome = HeadlessChrome.attach(cdp);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("连接已有浏览器失败（" + cdp + "）: " + e, e);
            }
        }

        Optional<Path> browser = BrowserLocator.find();
        assumeTrue(browser.isPresent(), "本机未找到无头浏览器，跳过浏览器冒烟测试。" + BrowserLocator.describe());
        try {
            chrome = HeadlessChrome.start(browser.get());
        } catch (IOException e) {
            // 找到了却起不来属于环境异常：必须失败，否则本机永远"假绿"
            throw new IllegalStateException("找到浏览器但启动失败: " + browser.get(), e);
        }
    }

    @AfterAll
    static void stopBrowser() {
        if (chrome != null) {
            chrome.close();
            chrome = null;
        }
    }

    @BeforeEach
    void setUp() {
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider("openai");
        config.setBaseUrl("http://127.0.0.1:" + port + "/mock");
        config.setModelId("mock-model");
        config.setDisplayName("Mock Model");
        config.setApiKey("mock-key");
        config.setActive(true);
        config.setModelType(ModelType.TEXT);
        configId = aiModelConfigRepository.save(config).getId();
        globalSettingRepository.save(new GlobalSettingEntity("default_model_config_id", configId.toString()));

        ProjectEntity project = new ProjectEntity();
        project.setTitle("浏览器冒烟测试项目");
        project.setGenre(Genre.XUANHUAN);
        project.setDescription("用于无头浏览器冒烟测试");
        project.setTotalChapters(3);
        project.setChapterWordCount(1000);
        project.setDefaultModelConfigId(configId);
        projectId = projectRepository.save(project).getId();

        InspirationEntity first = new InspirationEntity();
        first.setProjectId(projectId);
        first.setTitle("灵感：主角的第一次顿悟");
        first.setContent("可以让主角在雨夜的山道上，从一道雷光里悟出剑意。");
        first = inspirationRepository.save(first);
        inspirationId = first.getId();

        InspirationEntity second = new InspirationEntity();
        second.setProjectId(projectId);
        second.setTitle("灵感：反派的动机");
        second.setContent("反派并非纯粹邪恶，他的出发点是重建被毁的宗门。");
        inspirationRepository.save(second);
    }

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            Long pid = projectId;
            transactionTemplate.executeWithoutResult(status -> {
                inspirationRepository.deleteByProjectId(pid);
                projectRepository.deleteById(pid);
            });
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (configId != null) {
                aiModelConfigRepository.deleteById(configId);
            }
            globalSettingRepository.deleteById("default_model_config_id");
        });
    }

    // ============================ 页面冒烟 ============================

    /**
     * 打开页面 → 等哨兵 → 断言四类信号干净 → 执行补充断言。
     *
     * @param path        页面路径（相对根）
     * @param sentinel    哨兵 CSS 选择器，可为 null（改用 expectText 判定渲染完成）
     * @param expectText  页面必须渲染出的文案，证明 JS 真的跑完；可为 null
     * @param requireNav  是否要求顶部导航栏已挂载（沉浸式全屏页传 false）
     * @param extra       补充断言，在信号校验通过后执行；可为 null
     */
    protected void smoke(String path, String sentinel, String expectText, boolean requireNav,
                         Consumer<HeadlessChrome.Page> extra) {
        String url = "http://127.0.0.1:" + port + path;
        HeadlessChrome.Page page = openPage();
        boolean passed = false;
        try {
            page.navigate(url);

            if (sentinel != null && !page.waitForSelector(sentinel, RENDER_TIMEOUT)) {
                fail("页面 " + path + " 在 " + RENDER_TIMEOUT + " 内未渲染出哨兵元素 `" + sentinel + "`\n"
                        + diagnostics(page, path));
            }
            if (expectText != null && !waitForText(page, expectText, RENDER_TIMEOUT)) {
                fail("页面 " + path + " 在 " + RENDER_TIMEOUT + " 内未渲染出预期文案 `" + expectText + "`\n"
                        + diagnostics(page, path));
            }
            // 异步 fetch / 控制台输出可能晚于哨兵出现，留一段静默期再收网
            settle(500);

            assertClean(page, path, requireNav);
            if (extra != null) {
                extra.accept(page);
            }
            passed = true;
        } finally {
            if (!passed) {
                dumpArtifacts(page, path);
            }
            page.close();
        }
    }

    protected void smoke(String path, String sentinel, String expectText) {
        smoke(path, sentinel, expectText, true, null);
    }

    protected void assertClean(HeadlessChrome.Page page, String path, boolean requireNav) {
        assertThat(page.jsErrors())
                .as("页面 %s 存在未捕获的 JS 错误（如 SC.xxx is not a function、读取 null 属性）", path)
                .isEmpty();

        assertThat(page.consoleErrors())
                .as("页面 %s 存在 console.error 输出", path)
                .isEmpty();

        // favicon 缺失是浏览器默认行为，与应用无关，属于噪声
        List<String> failures = page.resourceFailures().stream()
                .filter(f -> !f.contains("favicon.ico"))
                .toList();
        assertThat(failures)
                .as("页面 %s 存在资源加载失败（如写死的 CDN 404）", path)
                .isEmpty();

        assertThat(page.externalUrls())
                .as("页面 %s 请求了外部资源，静态资源应全部本地化到 /vendor/", path)
                .isEmpty();

        if (requireNav) {
            // 导航栏由 nav.js 渲染；它一旦没挂上，上面的元素断言也可能全部落空
            assertThat(page.count("#site-nav a.navbar-brand"))
                    .as("页面 %s 的导航栏未被 nav.js 渲染（缺少 <header id=\"site-nav\"> 或 nav.js）", path)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    /** 轮询页面文本，直到出现期望文案（Alpine 异步渲染的兜底等待手段）。 */
    protected boolean waitForText(HeadlessChrome.Page page, String text, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String body = page.evaluate("document.body ? document.body.innerText : ''");
            if (body != null && body.contains(text)) {
                return true;
            }
            settle(200);
        }
        return false;
    }

    protected String bodyText(HeadlessChrome.Page page) {
        return page.evaluate("document.body ? document.body.innerText : ''");
    }

    /**
     * 打开标签页；浏览器进程已死时按"环境问题"跳过，而不是把构建搞红。
     *
     * <p>区分标准：浏览器进程已退出 → 环境起不来（沙箱 / 权限 / 资源限制），跳过；
     * 浏览器活着却开不了页 → 异常，失败并暴露完整堆栈。
     */
    protected HeadlessChrome.Page openPage() {
        try {
            return chrome.open();
        } catch (IOException e) {
            if (!chrome.isBrowserAlive()) {
                throw new org.opentest4j.TestAbortedException(
                        "无头浏览器进程已退出，跳过浏览器冒烟测试（多为沙箱/权限限制）: " + e);
            }
            throw new IllegalStateException("打开标签页失败: " + e, e);
        }
    }

    protected String diagnostics(HeadlessChrome.Page page, String path) {
        return "JS 错误: " + page.jsErrors()
                + "\nconsole.error: " + page.consoleErrors()
                + "\n资源失败: " + page.resourceFailures()
                + "\n外部请求: " + page.externalUrls()
                + "\n页面文本: " + abbreviate(bodyText(page), 400)
                + "\nDOM 片段: " + abbreviate(page.html(), 1200);
    }

    /** 失败时把 DOM 与截屏落到 target/browser-tests/，便于回溯。 */
    protected void dumpArtifacts(HeadlessChrome.Page page, String path) {
        try {
            Files.createDirectories(ARTIFACT_DIR);
            String name = path.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (name.isBlank()) {
                name = "root";
            }
            Files.writeString(ARTIFACT_DIR.resolve(name + ".html"), page.html(), StandardCharsets.UTF_8);
            page.screenshot(ARTIFACT_DIR.resolve(name + ".png"));
        } catch (IOException ignored) {
            // 留证失败不要掩盖真正的断言失败
        }
    }

    protected static String abbreviate(String s, int max) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    protected static void settle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
