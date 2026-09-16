package com.storycreator.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 极简 CDP（Chrome DevTools Protocol）驱动：零第三方依赖，只用 JDK 自带的
 * {@link HttpClient} WebSocket 与本机已安装的 Chrome/Chromium。
 *
 * <p>之所以不用 Playwright / Selenium：二者都需要联网下载驱动与浏览器实体，
 * 本项目要求离线可用，而本机 Chrome 的 {@code --headless=new --remote-debugging-port=0}
 * CDP 通路是现成的。
 *
 * <p>核心能力：开标签页 → 打开页面 → 等 JS 渲染哨兵 → 采集四类信号：
 * <ol>
 *   <li>{@code Runtime.exceptionThrown}：未捕获的运行时 JS 错误；</li>
 *   <li>{@code Runtime.consoleAPICalled} / {@code Log.entryAdded}（error 级）：console.error；</li>
 *   <li>{@code Network.responseReceived}(status≥400) / {@code loadingFailed}：资源 4xx/5xx 或加载失败；</li>
 *   <li>页面 DOM 求值：确认 JS 真的渲染出了元素。</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>{@code
 * try (HeadlessChrome chrome = HeadlessChrome.start(browserPath)) {
 *     try (HeadlessChrome.Page page = chrome.open()) {
 *         page.navigate(url);
 *         page.waitForSelector("#list .card", Duration.ofSeconds(10));
 *         assertThat(page.jsErrors()).isEmpty();
 *     }
 * }
 * }</pre>
 */
public final class HeadlessChrome implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(25);

    private final Path executable;
    private final Path userDataDir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Process process;
    private int port;

    /** 非空表示"连接外部已有浏览器"，此时本对象不负责启动与关闭。 */
    private final String endpoint;

    private HeadlessChrome(Path executable, Path userDataDir, String endpoint) {
        this.executable = executable;
        this.userDataDir = userDataDir;
        this.endpoint = endpoint;
    }

    /**
     * 启动一个无头 Chrome 实例（独立临时 user-data-dir，避免污染用户配置）。
     *
     * @throws IOException 启动失败或 CDP 端点在超时内不可用
     */
    public static HeadlessChrome start(Path executable) throws IOException {
        HeadlessChrome chrome = new HeadlessChrome(executable, createTempDir(), null);
        try {
            chrome.launch();
        } catch (IOException | RuntimeException e) {
            chrome.close();
            throw e instanceof IOException io ? io : new IOException("启动无头浏览器失败: " + e.getMessage(), e);
        }
        return chrome;
    }

    /**
     * 连接到一个<b>已经在运行</b>的浏览器（本对象不负责启动，也不负责关闭）。
     *
     * <p>适用场景：
     * <ul>
     *   <li>受限环境（沙箱 / CI）里测试进程无权拉起浏览器，可由外部预先启动；</li>
     *   <li>复用一个长驻浏览器，省去每个测试类重复冷启动；</li>
     *   <li>连接容器或远程机器上的 Chrome。</li>
     * </ul>
     *
     * @param endpoint CDP HTTP 端点，如 {@code http://127.0.0.1:9222}
     */
    public static HeadlessChrome attach(String endpoint) throws IOException {
        String normalized = endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        HeadlessChrome chrome = new HeadlessChrome(null, null, normalized);
        chrome.get(normalized + "/json/version"); // 探活：连不上就直接抛
        return chrome;
    }

    /** 新建一个标签页并连上它的 CDP WebSocket。 */
    public Page open() throws IOException {
        // 注意：新版 Chrome 的 /json/new 只接受 PUT，GET 会被拒绝。
        JsonNode target = MAPPER.readTree(put(baseUrl() + "/json/new?about:blank"));
        String wsUrl = target.path("webSocketDebuggerUrl").asText(null);
        String targetId = target.path("id").asText(null);
        if (wsUrl == null || targetId == null) {
            throw new IOException("创建标签页失败，响应: " + target);
        }
        Page page = new Page(this, targetId, wsUrl);
        page.enable();
        return page;
    }

    /**
     * 浏览器进程是否仍在运行。
     *
     * <p>用于区分两类失败：连不上浏览器属于<b>环境</b>问题（应跳过测试，不该让构建变红）；
     * 而浏览器活着但页面报错，才是真正的<b>应用</b>问题（应失败）。
     */
    public boolean isBrowserAlive() {
        if (endpoint != null) {
            try {
                get(endpoint + "/json/version");
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        return process != null && process.isAlive();
    }

    @Override
    public void close() {
        if (endpoint != null) {
            return; // 外部浏览器由调用方负责，本对象不关它
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        deleteRecursively(userDataDir);
    }

    // ============================ 内部实现 ============================

    private void launch() throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
                executable.toString(),
                "--headless=new",
                "--remote-debugging-port=0",
                "--user-data-dir=" + userDataDir,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--hide-scrollbars",
                "--mute-audio",
                // 关掉后台联网，避免浏览器自身的遥测请求混入"外部请求"断言
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-sync",
                "--metrics-recording-only",
                "--disable-breakpad",
                "--disable-crash-reporter",
                "--window-size=1280,900",
                "about:blank"));
        // Chrome 自带的沙箱在嵌套环境（Docker / CI / 外层已被沙箱包裹）中常无法初始化，
        // 表现为 "Failed to initialize sandbox" → GPU 进程崩溃 → 浏览器整体退出。
        // 测试实例只访问本机自建页面，默认关闭它；确需保留时用 -Dstory.chrome.noSandbox=false。
        if (!"false".equalsIgnoreCase(System.getProperty("story.chrome.noSandbox", "true"))) {
            cmd.add("--no-sandbox");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(userDataDir.resolve("chrome.log").toFile());
        // 【重要】不要改写子进程的 HOME。
        // 实测：把 HOME 指向临时目录后，Chrome 仍能启动、CDP 命令正常、连 data: URL 也能导航，
        // 但对 http:// 目标的导航会永久挂起（Page.navigate 不返回任何响应，直到超时）——
        // 推测其网络栈依赖 ~/Library/Application Support/Google 下的既有状态。
        // 曾为此排查良久，故在此明确：保持继承父进程的 HOME，不要"优化"成隔离目录。
        process = pb.start();

        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        Path portFile = userDataDir.resolve("DevToolsActivePort");
        while (System.nanoTime() < deadline) {
            if (Files.isReadable(portFile)) {
                List<String> lines = Files.readAllLines(portFile);
                if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                    port = Integer.parseInt(lines.get(0).trim());
                    break;
                }
            }
            if (!process.isAlive()) {
                throw new IOException("Chrome 进程已退出（" + executable + "），日志见 " + userDataDir.resolve("chrome.log"));
            }
            sleep(100);
        }
        if (port == 0) {
            throw new IOException("等待 DevToolsActivePort 超时（" + START_TIMEOUT + "）");
        }

        // 端口文件出现不代表 HTTP 端点就绪，再探一次 /json/version
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/json/version"))
                                .timeout(Duration.ofSeconds(3))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 还没起来，继续等
            }
            sleep(100);
        }
        throw new IOException("CDP HTTP 端点在 " + START_TIMEOUT + " 内不可用（port=" + port + "）");
    }

    private String baseUrl() {
        return endpoint != null ? endpoint : "http://127.0.0.1:" + port;
    }

    String get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GET " + url + " 被中断", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private String put(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PUT " + url + " 被中断", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("PUT " + url + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static Path createTempDir() throws IOException {
        return Files.createTempDirectory("story-chrome-");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录，清理失败不影响测试结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================ 标签页 ============================

    /** 一个浏览器标签页，负责导航、求值与信号采集。 */
    public final class Page implements AutoCloseable {

        private final String targetId;
        private final HttpClient wsClient;
        private final WebSocket ws;
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<Integer, CompletableFuture<ObjectNode>> pending = new ConcurrentHashMap<>();
        private final Map<String, String> requestUrls = new ConcurrentHashMap<>();

        private final List<String> requestedUrls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> jsErrors = Collections.synchronizedList(new ArrayList<>());
        private final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());
        private final List<String> resourceFailures = Collections.synchronizedList(new ArrayList<>());

        private volatile CountDownLatch loadLatch = new CountDownLatch(1);

        private Page(HeadlessChrome owner, String targetId, String wsUrl) {
            this.targetId = targetId;
            this.wsClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            this.ws = wsClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .join();
        }

        private void enable() {
            send("Page.enable", null);
            send("Runtime.enable", null);
            send("Log.enable", null);
            send("Network.enable", null);
        }

        /** 导航到指定 URL，并等待 load 事件。 */
        public void navigate(String url) {
            loadLatch = new CountDownLatch(1);
            ObjectNode params = MAPPER.createObjectNode();
            params.put("url", url);
            send("Page.navigate", params);
            awaitLoad(url);
        }

        /**
         * 等到选择器命中的元素出现为止。
         *
         * @return true 表示在超时内出现
         */
        public boolean waitForSelector(String selector, Duration timeout) {
            return waitFor("!!document.querySelector(" + jsString(selector) + ")", timeout);
        }

        /** 等到 JS 表达式求值为 true（每 100ms 轮询一次）。 */
        public boolean waitFor(String jsPredicate, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    if ("true".equalsIgnoreCase(evaluate(jsPredicate))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // 页面还没准备好，继续轮询
                }
                sleep(100);
            }
            return false;
        }

        /** 命中选择器的元素个数。 */
        public int count(String selector) {
            String v = evaluate("document.querySelectorAll(" + jsString(selector) + ").length");
            try {
                return v == null ? 0 : Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** 在页面上下文求值；返回字符串形式的结果（无法返回时给 null）。 */
        public String evaluate(String expression) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("expression", expression);
            params.put("returnByValue", true);
            params.put("awaitPromise", false);
            JsonNode result = send("Runtime.evaluate", params);
            JsonNode details = result.get("exceptionDetails");
            if (details != null && !details.isNull()) {
                throw new RuntimeException("JS 求值异常: "
                        + details.path("exception").path("description").asText(details.path("text").asText("")));
            }
            JsonNode value = result.path("result").path("value");
            return value.isNull() ? null : value.asText();
        }

        /** 当前 DOM 完整 HTML（失败排查留证用）。 */
        public String html() {
            try {
                String h = evaluate("document.documentElement.outerHTML");
                return h == null ? "" : h;
            } catch (RuntimeException e) {
                return "(获取 HTML 失败: " + e.getMessage() + ")";
            }
        }

        /** 截屏（失败排查留证用）。 */
        public void screenshot(Path file) {
            try {
                ObjectNode params = MAPPER.createObjectNode();
                params.put("format", "png");
                String data = send("Page.captureScreenshot", params).path("data").asText(null);
                if (data != null && file != null) {
                    if (file.getParent() != null) {
                        Files.createDirectories(file.getParent());
                    }
                    Files.write(file, Base64.getDecoder().decode(data));
                }
            } catch (Exception ignored) {
                // 留证失败不应让测试以另一种方式红掉
            }
        }

        // ---------------- 信号 ----------------

        /** 未捕获的运行时 JS 错误（含位置）。 */
        public List<String> jsErrors() {
            return List.copyOf(jsErrors);
        }

        /** console.error / Log error 级输出。 */
        public List<String> consoleErrors() {
            return List.copyOf(consoleErrors);
        }

        /** 资源加载失败：HTTP 4xx/5xx 与 loadingFailed。 */
        public List<String> resourceFailures() {
            return List.copyOf(resourceFailures);
        }

        /** 所有请求过的 URL（判断有没有外部依赖）。 */
        public List<String> requestedUrls() {
            return List.copyOf(requestedUrls);
        }

        /** 请求过的<b>非本机</b> URL（即潜在的 CDN / 外网依赖）。 */
        public List<String> externalUrls() {
            Set<String> local = Set.of("localhost", "127.0.0.1", "[::1]", "0.0.0.0", "");
            List<String> out = new ArrayList<>();
            for (String url : requestedUrls) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    continue;
                }
                try {
                    String host = URI.create(url).getHost();
                    if (host != null && !local.contains(host.toLowerCase(Locale.ROOT))) {
                        out.add(url);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 无法解析的 URL 不算外部依赖
                }
            }
            return out;
        }

        @Override
        public void close() {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "test done");
            } catch (Exception ignored) {
                // 忽略
            }
            try {
                get(baseUrl() + "/json/close/" + targetId);
            } catch (Exception ignored) {
                // 忽略
            }
        }

        // ---------------- CDP ----------------

        private void awaitLoad(String url) {
            try {
                if (!loadLatch.await(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    resourceFailures.add("等待 load 事件超时: " + url);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private JsonNode send(String method, ObjectNode params) {
            int id = seq.incrementAndGet();
            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("id", id);
            msg.put("method", method);
            if (params != null) {
                msg.set("params", params);
            }
            CompletableFuture<ObjectNode> future = new CompletableFuture<>();
            pending.put(id, future);
            ws.sendText(msg.toString(), true);
            try {
                ObjectNode response = future.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (response.hasNonNull("error")) {
                    throw new IllegalStateException("CDP " + method + " 失败: " + response.get("error"));
                }
                return response.path("result");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 CDP " + method + " 响应被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("CDP " + method + " 执行失败", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("CDP " + method + " 超时（" + COMMAND_TIMEOUT + "）", e);
            } finally {
                pending.remove(id);
            }
        }

        private void handle(String message) {
            JsonNode node;
            try {
                node = MAPPER.readTree(message);
            } catch (Exception e) {
                jsErrors.add("解析 CDP 消息失败: " + e.getMessage());
                return;
            }

            JsonNode idNode = node.get("id");
            if (idNode != null && !idNode.isNull()) {
                CompletableFuture<ObjectNode> future = pending.remove(idNode.asInt());
                if (future != null && node instanceof ObjectNode obj) {
                    future.complete(obj);
                }
                return;
            }

            JsonNode params = node.path("params");
            switch (node.path("method").asText("")) {
                case "Page.loadEventFired" -> loadLatch.countDown();
                case "Runtime.exceptionThrown" -> {
                    JsonNode ed = params.path("exceptionDetails");
                    String text = ed.path("exception").path("description").asText("");
                    if (text.isBlank()) {
                        text = ed.path("exception").path("value").asText(ed.path("text").asText("(无描述)"));
                    }
                    String url = ed.path("url").asText("");
                    jsErrors.add(text + (url.isBlank() ? "" : " @" + url + ":" + ed.path("lineNumber").asText("?")));
                }
                case "Runtime.consoleAPICalled" -> {
                    if (!"error".equals(params.path("type").asText(""))) {
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode arg : params.path("args")) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(arg.path("value").asText(arg.path("description").asText(arg.path("type").asText(""))));
                    }
                    consoleErrors.add(sb.toString());
                }
                case "Log.entryAdded" -> {
                    JsonNode entry = params.path("entry");
                    if (!"error".equals(entry.path("level").asText(""))) {
                        return;
                    }
                    String text = entry.path("text").asText("");
                    String url = entry.path("url").asText("");
                    consoleErrors.add("[log] " + text + (url.isBlank() ? "" : " @" + url));
                }
                case "Network.requestWillBeSent" -> {
                    String requestId = params.path("requestId").asText("");
                    String url = params.path("request").path("url").asText("");
                    requestUrls.put(requestId, url);
                    requestedUrls.add(url);
                }
                case "Network.responseReceived" -> {
                    JsonNode response = params.path("response");
                    int status = response.path("status").asInt(0);
                    if (status >= 400) {
                        String url = response.path("url").asText(
                                requestUrls.getOrDefault(params.path("requestId").asText(""), ""));
                        resourceFailures.add("HTTP " + status + " " + url);
                    }
                }
                case "Network.loadingFailed" -> {
                    String requestId = params.path("requestId").asText("");
                    resourceFailures.add("加载失败 " + params.path("errorText").asText("")
                            + " " + requestUrls.getOrDefault(requestId, "(未知 URL)"));
                }
                default -> {
                    // 其他事件不关心
                }
            }
        }

        private final class Listener implements WebSocket.Listener {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    handle(message);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                jsErrors.add("WebSocket 错误: " + error.getMessage());
            }
        }
    }

    private static String jsString(String s) {
        return MAPPER.getNodeFactory().textNode(s).toString();
    }
}
