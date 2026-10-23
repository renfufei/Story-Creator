package com.storycreator.shared;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「资源共享」存储层的安全边界测试。
 *
 * <p>这个模块的价值全在「不该出去的东西出不去」，所以测试重心不是 CRUD 的成功路径，
 * 而是**每一条拒绝规则**：路径穿越、绝对路径、盘符、控制字符、保留设备名、符号链接逃逸、
 * 超深路径、重名覆盖……
 *
 * <p>用 {@link TempDir} 起真实的临时目录，不碰 {@code ./data}，也不依赖 Spring。
 */
class SharedStorageServiceTest {

    @TempDir
    Path tempDir;

    private SharedStorageService storage;
    private Path sharedRoot;

    @BeforeEach
    void setUp() {
        // 直接 new：不经过 Spring，@PostConstruct 不会跑，正好验证「首次访问也能自动建目录」
        storage = new SharedStorageService(tempDir.toString());
        sharedRoot = tempDir.resolve(SharedStorageService.SHARED_DIR);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("根目录：空路径 / null 落在 shared 本身；以 / 开头的一律按绝对路径拒绝")
    void rootListing() {
        assertThat(storage.list("").path()).isEmpty();
        assertThat(storage.list(null).path()).isEmpty();
        assertThat(sharedRoot).as("首次访问应自动创建 shared 目录").isDirectory();
        // "/" 交给「绝对路径」规则拒绝，而不是默默当成根目录 —— 静默纠正会掩盖调用方的错误
        assertThatThrownBy(() -> storage.list("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("绝对路径");
    }

    @Test
    @DisplayName("上传 → 列表 → 下载 → 删除，全链路都把内容原样带回来")
    void uploadListDownloadDelete() throws IOException {
        storage.store("", java.util.List.of(file("hello.txt", "你好，共享世界")));

        SharedStorageService.Listing listing = storage.list("");
        assertThat(listing.fileCount()).isEqualTo(1);
        assertThat(listing.items()).extracting(SharedStorageService.Entry::name).containsExactly("hello.txt");
        assertThat(listing.items().get(0).size()).isEqualTo("你好，共享世界".getBytes(StandardCharsets.UTF_8).length);
        assertThat(listing.items().get(0).ext()).isEqualTo("txt");

        SharedStorageService.StoredFile f = storage.open("hello.txt");
        assertThat(Files.readString(f.path(), StandardCharsets.UTF_8)).isEqualTo("你好，共享世界");
        assertThat(f.name()).isEqualTo("hello.txt");

        storage.delete("hello.txt");
        assertThat(storage.list("").fileCount()).isZero();
    }

    @Test
    @DisplayName("子目录：新建 → 进入 → 上传到子目录 → 面包屑正确")
    void subDirectory() {
        storage.mkdir("docs");
        storage.store("docs", java.util.List.of(file("a.txt", "A")));

        SharedStorageService.Listing root = storage.list("");
        assertThat(root.dirCount()).isEqualTo(1);
        assertThat(root.items().get(0).directory()).isTrue();

        SharedStorageService.Listing sub = storage.list("docs");
        assertThat(sub.path()).isEqualTo("docs");
        assertThat(sub.name()).isEqualTo("docs");
        assertThat(sub.parent()).isEmpty();
        assertThat(sub.canGoUp()).isTrue();
        assertThat(sub.crumbs()).extracting(SharedStorageService.Crumb::name)
                .containsExactly("共享资源", "docs");
        assertThat(sub.items()).extracting(SharedStorageService.Entry::name).containsExactly("a.txt");
        // 下载时必须用相对 shared 的完整路径
        assertThat(storage.open("docs/a.txt").name()).isEqualTo("a.txt");
    }

    @Test
    @DisplayName("列表排序：目录在前，然后按中文/英文自然序")
    void listingOrder() {
        storage.mkdir("zzz-dir");
        storage.store("", java.util.List.of(file("b.txt", "b"), file("a.txt", "a")));
        storage.mkdir("aaa-dir");
        assertThat(storage.list("").items()).extracting(SharedStorageService.Entry::name)
                .containsExactly("aaa-dir", "zzz-dir", "a.txt", "b.txt");
    }

    @Test
    @DisplayName("以点开头的隐藏项不进列表：文件、目录、上传临时文件、.DS_Store 都不显示也不计数")
    void hiddenEntriesAreNotListed() throws IOException {
        storage.store("", java.util.List.of(file("可见.txt", "x"), file(".env", "SECRET")));
        Files.createDirectories(sharedRoot.resolve(".git"));
        Files.writeString(sharedRoot.resolve(".DS_Store"), "junk", StandardCharsets.UTF_8);
        // 模拟「上传进行到一半」留下的临时文件（正常路径下会被 finally 清掉，这里直接造一个）
        Files.writeString(sharedRoot.resolve(".upload-123.tmp"), "half", StandardCharsets.UTF_8);

        SharedStorageService.Listing listing = storage.list("");
        assertThat(listing.items()).extracting(SharedStorageService.Entry::name)
                .as("只有可见.txt 一个条目")
                .containsExactly("可见.txt");
        assertThat(listing.dirCount()).as("隐藏目录不计入目录数").isZero();
        assertThat(listing.fileCount()).as("隐藏文件不计入文件数").isEqualTo(1);
        assertThat(listing.totalBytes()).as("隐藏文件的字节数不进总量").isEqualTo("x".length());

        // 只过滤「显示」，不拦访问：隐藏文件仍然能按路径读到（这里不是权限控制）
        assertThat(storage.open(".env").name()).isEqualTo(".env");
    }

    // ==================== 路径穿越：一律拒绝 ====================

    @Test
    @DisplayName("路径穿越：..、混合分隔符、深层 ../ 全部拒绝")
    void rejectsTraversal() {
        for (String bad : new String[]{
                "..",
                "../",
                "../etc/passwd",
                "a/../../etc",
                "a/..",
                "docs/../../../tmp",
                "..\\..\\windows\\system32",
                "a\\..\\b",
                "a//../b",
                "./../x"}) {
            assertThatThrownBy(() -> storage.list(bad))
                    .as("应拒绝穿越路径: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("绝对路径与盘符：/etc/passwd、C:\\、file: 一律拒绝")
    void rejectsAbsolutePaths() {
        for (String bad : new String[]{
                "/etc/passwd",
                "/",
                "//server/share",
                "C:\\Windows\\win.ini",
                "c:/temp",
                "file:///etc/passwd",
                "http://evil.example/x"}) {
            assertThatThrownBy(() -> storage.list(bad))
                    .as("应拒绝绝对/带协议路径: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("非法字符与控制字符：< > : \" | ? * 与 \\0 一律拒绝")
    void rejectsIllegalChars() {
        for (String bad : new String[]{
                "a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b",
                "a\u0000b", "a\u0001b", "a\u007fb"}) {
            assertThatThrownBy(() -> storage.list(bad))
                    .as("应拒绝含非法字符的路径: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("段级规则：空名、段尾点/空格、系统保留设备名、超长名都拒绝")
    void rejectsBadSegments() {
        assertThatThrownBy(() -> storage.mkdir("a.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.mkdir("a ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.mkdir("CON")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.mkdir("nul.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.mkdir("LPT9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.mkdir("x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称过长");
    }

    @Test
    @DisplayName("超深路径（> 16 层）拒绝，防止一层层把 IO 拖死")
    void rejectsTooDeep() {
        String deep = "a".repeat(1) + "/b".repeat(20);
        assertThatThrownBy(() -> storage.mkdir(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("下载只认普通文件：目录、不存在、根目录都不给")
    void downloadRejectsNonFiles() {
        storage.mkdir("docs");
        assertThatThrownBy(() -> storage.open("docs"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不是文件");
        assertThatThrownBy(() -> storage.open("nope.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> storage.open(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("下载接口同样挡穿越：拿不到 shared 之外的任何文件")
    void downloadRejectsTraversal() throws IOException {
        // 在 shared 的**父目录**放一个「机密」文件，确认从任何路径都摸不到它
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");

        for (String bad : new String[]{
                "../secret.txt",
                "docs/../../secret.txt",
                "./../secret.txt"}) {
            assertThatThrownBy(() -> storage.open(bad))
                    .as("应拒绝下载: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(storage.list("").items()).extracting(SharedStorageService.Entry::name)
                .as("父目录的文件不该出现在列表里")
                .doesNotContain("secret.txt");
    }

    // ==================== 符号链接逃逸 ====================

    @Test
    @DisplayName("符号链接逃逸：shared 里指向外部的软链不列出、不能进入、不能借它下载")
    void symbolicLinkCannotEscape() throws IOException {
        storage.list("");                       // 先把 shared 建出来
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("leak.txt"), "LEAK");
        Path link = sharedRoot.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // 少数环境（无权限 / 不支持软链）跳过，别让环境问题变成红灯
            return;
        }

        assertThat(storage.list("").items()).extracting(SharedStorageService.Entry::name)
                .as("符号链接不该出现在列表里").doesNotContain("link");

        assertThatThrownBy(() -> storage.list("link"))
                .as("不能进入指向外部的软链").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("link/leak.txt"))
                .as("不能借软链把外部文件下载出去").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("link"))
                .as("不允许通过接口操作软链（只读忽略，删除请手工）")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 上传细节 ====================

    @Test
    @DisplayName("上传重名：自动加 (1)、(2)，绝不覆盖已有文件")
    void uploadNeverOverwrites() throws IOException {
        storage.store("", java.util.List.of(file("a.txt", "第一次")));
        storage.store("", java.util.List.of(file("a.txt", "第二次")));
        storage.store("", java.util.List.of(file("a.txt", "第三次")));

        assertThat(storage.list("").items()).extracting(SharedStorageService.Entry::name)
                .containsExactlyInAnyOrder("a.txt", "a (1).txt", "a (2).txt");
        assertThat(Files.readString(sharedRoot.resolve("a.txt"), StandardCharsets.UTF_8)).isEqualTo("第一次");
    }

    @Test
    @DisplayName("上传文件名净化：带路径的原始名只取最后一段，非法字符换成下划线")
    void sanitizeUploadNames() {
        assertThat(SharedStorageService.sanitizeUploadName("../../evil.txt")).isEqualTo("evil.txt");
        assertThat(SharedStorageService.sanitizeUploadName("C:\\temp\\a.txt")).isEqualTo("a.txt");
        assertThat(SharedStorageService.sanitizeUploadName("a<b>c.txt")).isEqualTo("a_b_c.txt");
        assertThat(SharedStorageService.sanitizeUploadName("a\u0000b.txt")).isEqualTo("a_b.txt");
        assertThat(SharedStorageService.sanitizeUploadName("..")).isEqualTo("未命名文件");
        assertThat(SharedStorageService.sanitizeUploadName(null)).isEqualTo("未命名文件");
        assertThat(SharedStorageService.sanitizeUploadName("trailing.")).isEqualTo("trailing");
        assertThat(SharedStorageService.sanitizeUploadName("nul.txt")).isEqualTo("_nul.txt");
    }

    @Test
    @DisplayName("上传时把「带 ../ 的原始文件名」净化后落在目标目录内，不会跳到上一层")
    void uploadWithTraversalNameStaysInside() {
        storage.mkdir("docs");
        storage.store("docs", java.util.List.of(file("../../evil.txt", "x")));

        assertThat(sharedRoot.resolve("docs").resolve("evil.txt")).as("文件应落在 docs 内").exists();
        assertThat(tempDir.resolve("evil.txt")).as("不该写到 shared 的上一层").doesNotExist();
        assertThat(sharedRoot.resolve("evil.txt")).as("也不该落在 shared 根上").doesNotExist();
    }

    @Test
    @DisplayName("超过单文件上限的直接拒绝（不落盘）")
    void rejectsOversizedFile() {
        MultipartFile huge = new MockMultipartFile("files", "big.bin", "application/octet-stream",
                new byte[]{1, 2, 3}) {
            @Override
            public long getSize() {
                return SharedStorageService.MAX_FILE_BYTES + 1;
            }
        };
        assertThatThrownBy(() -> storage.store("", java.util.List.of(huge)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");
        assertThat(storage.list("").items()).as("超限文件不该留在目录里").isEmpty();
    }

    @Test
    @DisplayName("空上传：没有文件时明确报错，而不是静默成功")
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> storage.store("", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有收到文件");
        MultipartFile empty = new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> storage.store("", java.util.List.of(empty)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 新建 / 删除 ====================

    @Test
    @DisplayName("新建文件夹：同名报冲突，父目录不存在报 404")
    void mkdirRules() {
        storage.mkdir("docs");
        assertThatThrownBy(() -> storage.mkdir("docs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
        assertThatThrownBy(() -> storage.mkdir("nope/child"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> storage.mkdir(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("删除：文件与空目录可以删，非空目录给 409，根目录不给删")
    void deleteRules() {
        storage.mkdir("docs");
        storage.store("docs", java.util.List.of(file("a.txt", "A")));

        assertThatThrownBy(() -> storage.delete("docs"))
                .as("非空目录必须先清空").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.delete(""))
                .as("根目录不可删").isInstanceOf(IllegalArgumentException.class);

        storage.delete("docs/a.txt");
        assertThatCode(() -> storage.delete("docs")).doesNotThrowAnyException();
        assertThat(sharedRoot.resolve("docs")).doesNotExist();
    }

    @Test
    @DisplayName("删除不存在的目标：404，而不是静默成功")
    void deleteMissing() {
        assertThatThrownBy(() -> storage.delete("ghost.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("中文与空格文件名可以正常存取（共享场景里最常见）")
    void handlesChineseNames() throws IOException {
        storage.store("", java.util.List.of(file("会议纪要 2026-10-21.txt", "内容")));
        SharedStorageService.Listing listing = storage.list("");
        assertThat(listing.items()).extracting(SharedStorageService.Entry::name)
                .containsExactly("会议纪要 2026-10-21.txt");
        assertThat(storage.open("会议纪要 2026-10-21.txt").size()).isGreaterThan(0);
        assertThat(Files.readString(sharedRoot.resolve("会议纪要 2026-10-21.txt"), StandardCharsets.UTF_8))
                .isEqualTo("内容");
    }

    @Test
    @DisplayName("列表里不显示上传过程中的临时文件")
    void hidesTempFiles() throws IOException {
        storage.list("");
        Files.writeString(sharedRoot.resolve(".upload-123.tmp"), "半截");
        assertThat(storage.list("").items()).isEmpty();
    }

    @Test
    @DisplayName("审计：未经 shared 的路径解析一律拒绝绝对路径（不泄露服务器目录）")
    void errorMessagesDoNotLeakAbsolutePath() {
        try {
            storage.list("../secret");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage())
                    .as("报错只说哪一项不合法，不要回显解析后的绝对路径")
                    .doesNotContain(tempDir.toString());
        }
    }
}
