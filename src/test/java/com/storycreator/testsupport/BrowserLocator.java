package com.storycreator.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 定位本机可用的无头浏览器（Chrome / Chromium / Edge 等）。
 *
 * <p>设计原则：<b>找不到浏览器时"跳过"而不是"失败"</b>。浏览器冒烟测试属于环境增强，
 * 没有浏览器的机器（如 CI 容器）跑 {@code mvn test} 应该是绿的，而不是因为缺 Chrome 红掉。
 *
 * <p>优先级：
 * <ol>
 *   <li>环境变量 {@code STORY_BROWSER_PATH} 显式指定（存在且可执行才用）；</li>
 *   <li>常见安装路径扫描（macOS / Linux）；</li>
 *   <li>{@code PATH} 上的 {@code google-chrome} / {@code chromium} 等命令。</li>
 * </ol>
 */
public final class BrowserLocator {

    /** 显式指定浏览器可执行文件绝对路径。 */
    public static final String ENV_PATH = "STORY_BROWSER_PATH";

    /** 设为 {@code true} 强制跳过浏览器测试（CI / 临时排查用）。 */
    public static final String ENV_SKIP = "STORY_SKIP_BROWSER_TESTS";

    /** 与 ENV_SKIP 等价的系统属性形式（方便 -D 传入）。 */
    public static final String PROP_SKIP = "story.skipBrowserTests";

    /**
     * 直连一个<b>已经在运行</b>的浏览器 CDP 端点（如 {@code http://127.0.0.1:9222}）。
     * 设置后不再自行启动浏览器，适用于沙箱 / CI 等测试进程无权拉起 Chrome 的环境。
     */
    public static final String ENV_CDP_URL = "STORY_BROWSER_CDP_URL";

    private static final List<String> CANDIDATES = List.of(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/Applications/Arc.app/Contents/MacOS/Arc",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/local/bin/google-chrome",
            "/opt/homebrew/bin/chromium",
            "/snap/bin/chromium"
    );

    private static final List<String> PATH_COMMANDS = List.of(
            "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome"
    );

    private BrowserLocator() {
    }

    /** 是否显式要求跳过（环境变量或系统属性任一为 true）。 */
    public static boolean skipRequested() {
        return Boolean.parseBoolean(System.getenv(ENV_SKIP))
                || Boolean.parseBoolean(System.getProperty(PROP_SKIP));
    }

    /**
     * 查找可用浏览器。
     *
     * @return 可执行文件路径；找不到返回 {@link Optional#empty()}
     */
    public static Optional<Path> find() {
        if (skipRequested()) {
            return Optional.empty();
        }

        String explicit = System.getenv(ENV_PATH);
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit.trim());
            return Files.isExecutable(p) ? Optional.of(p.toAbsolutePath().normalize()) : Optional.empty();
        }

        for (String candidate : CANDIDATES) {
            Path p = Path.of(candidate);
            if (Files.isExecutable(p)) {
                return Optional.of(p.toAbsolutePath().normalize());
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String cmd : PATH_COMMANDS) {
                    Path p = Path.of(dir, cmd);
                    if (Files.isExecutable(p)) {
                        return Optional.of(p.toAbsolutePath().normalize());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 给跳过消息用的可读说明，帮助定位"为什么没跑起来"。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("已扫描常见安装路径（Chrome/Chromium/Edge/Brave/Arc）与 PATH 命令");
        String explicit = System.getenv(ENV_PATH);
        if (explicit != null && !explicit.isBlank()) {
            sb.append("；STORY_BROWSER_PATH=").append(explicit)
              .append(Files.isExecutable(Path.of(explicit.trim())) ? "（可执行）" : "（不可执行）");
        }
        sb.append("。可用 ").append(ENV_PATH).append(" 显式指定，或用 ").append(ENV_SKIP).append("=true 静默跳过。");
        return sb.toString();
    }
}
