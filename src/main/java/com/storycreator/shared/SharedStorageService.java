package com.storycreator.shared;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 「资源共享」的存储层 —— 把 {@code ${STORY_DB_PATH}/shared} 当作**唯一根目录**，对外只暴露这一棵子树。
 *
 * <p><b>为什么不做数据库表？</b>共享的是「就地放进去的文件」——用户在 Finder / 命令行里
 * 丢进 {@code shared/} 的东西必须立刻可见，数据库做不到这一点（还要额外同步）。
 * 所以文件系统本身就是唯一真相，这里只负责「安全地把它摊给浏览器」。
 *
 * <h3>路径安全：五道防线</h3>
 * <ol>
 *   <li><b>字符层</b>：拒绝 NUL 与控制字符、Windows 保留字符 {@code < > : " | ? *}、
 *       绝对路径（以 {@code /} 开头）与盘符路径（{@code C:\...}）。</li>
 *   <li><b>段层</b>：拆成 {@code /} 分隔的段，<b>遇到 {@code ..} 直接拒绝</b>（不是 normalize 掉 ——
 *       用户明确要求「不接受 ../ 等非法字符」，静默修正会掩盖攻击尝试）；
 *       同时拒绝空名、超长名、段尾的 {@code .} 与空格（Windows 上会被静默截断，制造同义路径）、
 *       以及 {@code CON/PRN/AUX/NUL/COM1..9/LPT1..9} 这类设备名。</li>
 *   <li><b>规范化</b>：{@code root.resolve(rel).normalize()} 之后 {@code startsWith(root)}
 *       字面校验，挡住任何漏网的组合。</li>
 *   <li><b>真实路径</b>：对**已存在**的路径再 {@code toRealPath()} 校验一次 —— 这条才是挡
 *       符号链接逃逸的关键（用户在 shared 里放一个指向 {@code /etc} 的软链，前两道都看不出来）。</li>
 *   <li><b>类型层</b>：判定文件/目录一律 {@code NOFOLLOW_LINKS}，符号链接本身既不能下载、
 *       也不能进入、也不会出现在列表里。</li>
 * </ol>
 *
 * <p>任何一道不过都抛 {@link IllegalArgumentException}（→ 400）或
 * {@link ResponseStatusException}（→ 404），异常文案只说「哪一项不合法」，
 * **不回显解析后的绝对路径**，避免把服务器目录结构泄露给来访者。
 */
@Service
public class SharedStorageService {

    private static final Logger log = LoggerFactory.getLogger(SharedStorageService.class);

    /** 目标目录名：固定挂在 STORY_DB_PATH 下面。 */
    public static final String SHARED_DIR = "shared";

    /** 单文件上限（与 application.yml 的 multipart 上限同量级；流式落盘时会再核一次真实字节数）。 */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    /** 目录层级上限，防「a/a/a/...」式的超深路径把 IO 拖死。 */
    private static final int MAX_DEPTH = 16;

    /** 单段名称长度上限（按字符数保守取值，UTF-8 下仍远低于常见的 255 字节限制）。 */
    private static final int MAX_NAME_CHARS = 200;

    /** 上传中我们自己产生的临时文件前缀，列表里要过滤掉。 */
    private static final String TMP_PREFIX = ".upload-";
    private static final String TMP_SUFFIX = ".tmp";

    /** Windows 保留设备名（macOS/Linux 上无意义，但保持跨平台一致，也避免这些名字落到共享盘后再炸）。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 路径里一律不允许出现的字符（{@code /} 与 {@code \} 在拆段前已统一成 {@code /}）。 */
    private static final String FORBIDDEN_CHARS = "<>:\"|?*";

    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:");

    /** 绝对路径、协议头（{@code file:} / {@code http:}）都先掐掉。 */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private final Path root;
    private volatile Path rootReal;

    public SharedStorageService(@Value("${STORY_DB_PATH:./data}") String dbPath) {
        this.root = Paths.get(dbPath).toAbsolutePath().normalize().resolve(SHARED_DIR);
    }

    /**
     * 启动时就把目录建好 —— 用户常常先在 Finder 里放文件、再打开页面，
     * 如果等第一次访问才创建，那个「先放」的动作会失败。
     * 建不出来只 warn（例如挂载盘还没就绪），不影响应用启动，首次访问时还会再试一次。
     */
    @PostConstruct
    void prepareRoot() {
        try {
            Files.createDirectories(root);
            log.info("共享资源根目录: {}", root.toRealPath());
        } catch (IOException e) {
            log.warn("共享资源目录暂时不可用（首次访问时会重试）: {}", root, e);
        }
    }

    // ==================== 对外模型 ====================

    /** 一个面包屑层级。{@code path} 是相对 shared 的路径（根为 ""）。 */
    public record Crumb(String name, String path) {
    }

    /**
     * 列表里的一项。{@code size} 对目录恒为 0。
     *
     * <p>{@code createdAt} 取 {@link BasicFileAttributes#creationTime()}（macOS 上是文件真正的
     * 创建时间；某些文件系统不支持时会回落成最后修改时间，此时两个字段相等 —— 前端排序照常工作）。
     * 任一属性读不到时该字段为 {@code null}，交给前端排到最后，**不让整个列表 500**。
     */
    public record Entry(String name, String path, boolean directory, long size,
                        String modifiedAt, String createdAt, String ext) {
    }

    /** 一次目录浏览的完整结果（前端一个请求就能把页头、统计、列表全渲染出来）。 */
    public record Listing(String path, String name, String parent, boolean canGoUp,
                          List<Crumb> crumbs, int dirCount, int fileCount, long totalBytes,
                          List<Entry> items) {
    }

    /** 待下发的文件。 */
    public record StoredFile(Path path, String name, long size) {
    }

    // ==================== 根目录 ====================

    /**
     * 取根目录的真实路径。**首次访问才创建**（不是构造器里）——
     * 让目录不可写只影响本模块报 503，而不是把整个应用启动搞挂。
     */
    private Path requireRoot() {
        Path r = rootReal;
        if (r != null) {
            return r;
        }
        synchronized (this) {
            if (rootReal == null) {
                try {
                    Files.createDirectories(root);
                    rootReal = root.toRealPath();
                    log.info("共享资源根目录: {}", rootReal);
                } catch (IOException e) {
                    log.error("共享资源目录不可用: {}", root, e);
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "共享资源目录不可用，请检查 " + SHARED_DIR + " 目录权限");
                }
            }
            return rootReal;
        }
    }

    /** 根目录的绝对路径（供日志/诊断用，不下发给前端）。 */
    public Path rootPath() {
        return requireRoot();
    }

    // ==================== 路径解析（安全核心） ====================

    private static IllegalArgumentException invalid(String msg) {
        return new IllegalArgumentException("路径不合法：" + msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    /**
     * 把前端传来的相对路径拆成**已校验的段**。空串 / {@code null} / {@code /} 都表示根目录。
     *
     * <p>这是整条链上唯一的入口：任何对外方法都必须先过这里，别在别处直接 resolve 字符串。
     */
    static List<String> splitSegments(String raw) {
        if (raw == null) {
            return List.of();
        }
        String s = raw.replace('\\', '/');
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.startsWith("/")) {
            throw invalid("不允许绝对路径");
        }
        if (DRIVE_LETTER.matcher(s).find() || SCHEME.matcher(s).find()) {
            throw invalid("不允许带盘符或协议的路径");
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                throw invalid("包含控制字符");
            }
            if (FORBIDDEN_CHARS.indexOf(c) >= 0) {
                throw invalid("包含非法字符 " + c);
            }
        }
        List<String> segments = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;                       // "a//b"、"a/./b" 视同 "a/b"
            }
            if ("..".equals(seg)) {
                throw invalid("不允许 .. 片段");
            }
            validateSegment(seg);
            segments.add(seg);
        }
        if (segments.size() > MAX_DEPTH) {
            throw invalid("目录层级过深（最多 " + MAX_DEPTH + " 层）");
        }
        return segments;
    }

    /** 单段名称校验（新建文件夹、上传文件名、路径的每一段都走它）。 */
    private static void validateSegment(String seg) {
        if (seg.isBlank()) {
            throw invalid("名称不能为空");
        }
        if (seg.length() > MAX_NAME_CHARS) {
            throw invalid("名称过长（最多 " + MAX_NAME_CHARS + " 个字符）");
        }
        if (seg.endsWith(".") || seg.endsWith(" ")) {
            // Windows 会把 "a." / "a " 静默变成 "a"，与已存在的 "a" 形成同义路径
            throw invalid("名称不能以点或空格结尾");
        }
        String stem = seg;
        int dot = seg.indexOf('.');
        if (dot > 0) {
            stem = seg.substring(0, dot);
        }
        if (RESERVED_NAMES.contains(stem.trim().toUpperCase(Locale.ROOT))) {
            throw invalid("名称与系统保留字冲突：" + seg);
        }
    }

    /**
     * 相对路径 → root 内的绝对路径（**字面**校验，不解析符号链接）。
     *
     * @param mustExist false 时允许最后一段不存在（上传/新建场景）
     */
    private Path resolveWithin(List<String> segments, boolean mustExist) {
        Path rootDir = requireRoot();
        Path target = segments.isEmpty() ? rootDir : rootDir.resolve(String.join("/", segments)).normalize();
        if (!target.startsWith(rootDir)) {
            throw invalid("越界：目标不在共享目录内");
        }
        if (mustExist && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("目标不存在");
        }
        return target;
    }

    /**
     * 真实路径校验：把存在的那部分解析成真实路径后再比一次。
     *
     * <p>这是挡符号链接逃逸的唯一有效手段 —— 例如 {@code shared/link -> /etc}，
     * 字面路径 {@code shared/link/passwd} 仍然 startsWith(shared)，只有 realpath 才看得出问题。
     */
    private Path requireInsideRealPath(Path target) {
        Path rootDir = requireRoot();
        Path real;
        try {
            real = target.toRealPath();
        } catch (NoSuchFileException e) {
            throw notFound("目标不存在");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法解析目标路径");
        }
        if (!real.startsWith(rootDir)) {
            throw invalid("越界：目标不在共享目录内");
        }
        if (Files.isSymbolicLink(target)) {
            // 链接本身不提供任何能力（不下载、不进入），避免把「共享目录」变成跳板
            throw invalid("不允许访问符号链接");
        }
        return real;
    }

    /** 解析一个**必须存在**的目录。 */
    private Path resolveDirectory(String relativePath) {
        Path target = resolveWithin(splitSegments(relativePath), true);
        Path real = requireInsideRealPath(target);
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("不是目录");
        }
        return real;
    }

    /** 相对路径（从 shared 起算）→ 前端用的规范形式。 */
    private static String toRelative(Path rootDir, Path target) {
        Path rel = rootDir.relativize(target);
        return rel.toString().replace(target.getFileSystem().getSeparator(), "/");
    }

    // ==================== 列表 ====================

    /** 浏览一个目录。 */
    public Listing list(String relativePath) {
        Path rootDir = requireRoot();
        List<String> segments = splitSegments(relativePath);
        Path dir = resolveDirectory(relativePath);

        List<Entry> items = new ArrayList<>();
        int dirCount = 0;
        int fileCount = 0;
        long totalBytes = 0;

        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(TMP_PREFIX) && name.endsWith(TMP_SUFFIX)) {
                    continue;                       // 上传中的临时文件，别让用户看到半截文件
                }
                // 符号链接一律不展示：既不能下载也不能进入，显示出来只会误导
                if (Files.isSymbolicLink(child)) {
                    log.warn("共享目录里的符号链接已忽略: {}", child);
                    continue;
                }
                boolean isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                long size = 0;
                String modifiedAt = null;
                String createdAt = null;
                try {
                    // 目录也读 BasicFileAttributes（以前只读 lastModified，拿不到创建时间）
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    size = isDir ? 0 : attrs.size();   // 目录大小不是「里面有多少内容」，一律 0
                    modifiedAt = attrs.lastModifiedTime().toInstant().toString();
                    createdAt = attrs.creationTime().toInstant().toString();
                } catch (IOException e) {
                    if (!isDir) {
                        // 文件读不到属性（权限/竞态删除）就不显示，别让整个列表 500
                        continue;
                    }
                    // 目录读不到就少两个时间字段，仍然允许进入
                }
                String rel = toRelative(rootDir, child);
                items.add(new Entry(name, rel, isDir, size, modifiedAt, createdAt,
                        isDir ? "" : extensionOf(name)));
                if (isDir) {
                    dirCount++;
                } else {
                    fileCount++;
                    totalBytes += size;
                }
            }
        } catch (IOException e) {
            log.error("读取共享目录失败: {}", dir, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取目录失败");
        }

        // 目录优先，然后按中文/英文的自然顺序（String.compareTo 会把中文排到 ASCII 后面，很难用）
        Collator collator = Collator.getInstance(Locale.CHINA);
        items.sort(Comparator
                .comparing(Entry::directory, Comparator.reverseOrder())
                .thenComparing(Entry::name, collator));

        String currentPath = segments.isEmpty() ? "" : String.join("/", segments);
        String parent = segments.isEmpty() ? null : String.join("/", segments.subList(0, segments.size() - 1));

        List<Crumb> crumbs = new ArrayList<>();
        crumbs.add(new Crumb("共享资源", ""));
        for (int i = 0; i < segments.size(); i++) {
            crumbs.add(new Crumb(segments.get(i), String.join("/", segments.subList(0, i + 1))));
        }

        return new Listing(currentPath,
                segments.isEmpty() ? "共享资源" : segments.get(segments.size() - 1),
                parent, !segments.isEmpty(), crumbs, dirCount, fileCount, totalBytes, items);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() > 8 ? "" : ext;
    }

    // ==================== 上传 ====================

    /**
     * 上传一批文件到指定目录。
     *
     * <p>先写到同目录下的临时文件再 {@code ATOMIC_MOVE} 成正式名 ——
     * 中途断线不会留下「看起来完整、其实只有一半」的文件（共享场景里最容易误导别人）。
     */
    public List<Entry> store(String dirRelativePath, List<MultipartFile> files) {
        Path dir = resolveDirectory(dirRelativePath);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有收到文件");
        }
        List<Entry> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("文件过大：" + file.getOriginalFilename()
                        + "（单个文件上限 " + (MAX_FILE_BYTES / 1024 / 1024) + " MB）");
            }
            String name = sanitizeUploadName(file.getOriginalFilename());
            saved.add(writeOne(dir, name, file));
        }
        if (saved.isEmpty()) {
            throw new IllegalArgumentException("没有收到有效文件");
        }
        return saved;
    }

    private Entry writeOne(Path dir, String name, MultipartFile file) {
        Path tmp = null;
        try {
            Path target = uniqueTarget(dir, name);
            tmp = Files.createTempFile(dir, TMP_PREFIX, TMP_SUFFIX);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            // 不信任 Content-Length：以落盘后的真实字节数为准
            long written = Files.size(tmp);
            if (written > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("文件过大：" + name
                        + "（单个文件上限 " + (MAX_FILE_BYTES / 1024 / 1024) + " MB）");
            }
            // ★ 故意不带 ATOMIC_MOVE：同目录内的 move 本身就是 rename（原子），
            //   而 ATOMIC_MOVE 在 Unix 上是「覆盖式 rename」—— 会静默盖掉同名文件，
            //   uniqueTarget 的重名保护就白做了。不带选项时目标已存在会抛 FileAlreadyExistsException。
            try {
                Files.move(tmp, target);
            } catch (FileAlreadyExistsException e) {
                // 并发同名：重算一次名字再落盘
                Path retry = uniqueTarget(dir, name);
                Files.move(tmp, retry);
                target = retry;
            }
            tmp = null;
            String rel = toRelative(requireRoot(), target);
            String[] times = fileTimes(target);
            log.info("共享资源上传: {} ({} bytes)", rel, written);
            return new Entry(target.getFileName().toString(), rel, false, written,
                    times[0], times[1], extensionOf(name));
        } catch (IOException e) {
            log.error("写入共享文件失败: {}", name, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程，最多留个临时文件（列表里已过滤）
                }
            }
        }
    }

    /**
     * 上传文件名净化：只取最后一段（IE/老浏览器会带完整本地路径），
     * 非法字符替换成 {@code _}，末尾的点与空格去掉；净化后仍为空才拒绝。
     */
    static String sanitizeUploadName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f || FORBIDDEN_CHARS.indexOf(c) >= 0 || c == '/') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        name = sb.toString().trim();
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "未命名文件";
        }
        if (name.length() > MAX_NAME_CHARS) {
            String ext = extensionOf(name);
            int keep = MAX_NAME_CHARS - (ext.isEmpty() ? 0 : ext.length() + 1);
            name = name.substring(0, Math.max(1, keep)) + (ext.isEmpty() ? "" : "." + ext);
        }
        String stem = name;
        int dot = name.indexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
        }
        if (RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            name = "_" + name;
        }
        return name;
    }

    /** 重名时在扩展名前追加 {@code  (1)}、{@code  (2)} …（最多试 999 次）。 */
    private Path uniqueTarget(Path dir, String name) {
        Path rootDir = requireRoot();
        Path candidate = dir.resolve(name).normalize();
        if (!candidate.startsWith(rootDir)) {
            throw invalid("越界：目标不在共享目录内");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            Path p = dir.resolve(base + " (" + i + ")" + ext).normalize();
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return p;
            }
        }
        throw new IllegalStateException("同名文件过多，请先整理目录");
    }

    // ==================== 下载 ====================

    /** 解析一个必须存在的**普通文件**（目录、符号链接、越界路径都拒绝）。 */
    public StoredFile open(String relativePath) {
        List<String> segments = splitSegments(relativePath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        Path target = resolveWithin(segments, true);
        Path real = requireInsideRealPath(target);
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("不是文件");
        }
        try {
            return new StoredFile(real, real.getFileName().toString(), Files.size(real));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取文件失败");
        }
    }

    // ==================== 新建 / 删除 ====================

    /** 新建目录（父目录必须已存在 —— 不隐式创建中间层，避免误输入产生一串空目录）。 */
    public Entry mkdir(String relativePath) {
        Path rootDir = requireRoot();
        List<String> segments = splitSegments(relativePath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("请填写文件夹名称");
        }
        Path target = resolveWithin(segments, false);
        // 父目录必须真实存在于 shared 内（含符号链接校验）
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("父目录不合法");
        }
        Path parentReal = requireInsideRealPath(parent);
        if (!Files.isDirectory(parentReal, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("父目录不存在");
        }
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("同名文件夹已存在");
        } catch (IOException e) {
            log.error("新建共享目录失败: {}", target, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "新建文件夹失败");
        }
        String rel = toRelative(rootDir, target);
        String[] times = fileTimes(target);
        return new Entry(segments.get(segments.size() - 1), rel, true, 0, times[0], times[1], "");
    }

    /**
     * 读一个路径的 {@code [最后修改时间, 创建时间]}（ISO-8601 字符串，读不到给 {@code null}）。
     *
     * <p>合并成一处，是因为上传 / 新建目录 / 列表三处都要这两个值，
     * 而「读不到属性」在任何一处都不该让整个请求失败。
     */
    private static String[] fileTimes(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new String[]{
                    attrs.lastModifiedTime().toInstant().toString(),
                    attrs.creationTime().toInstant().toString()
            };
        } catch (IOException e) {
            return new String[]{null, null};
        }
    }

    /**
     * 删除文件或**空**目录。
     *
     * <p>不提供递归删除：共享目录里往往是别人放的东西，一个误点就整片消失的代价太高。
     * 非空目录返回 409，请用户先清空。
     */
    public void delete(String relativePath) {
        List<String> segments = splitSegments(relativePath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("不能删除共享根目录");
        }
        Path target = resolveWithin(segments, true);
        // 注意：这里**先看是不是符号链接**再 realpath —— 删链接本身是安全操作，
        // 但如果先 realpath 就会跳到链接指向的外部文件上去删。
        if (Files.isSymbolicLink(target)) {
            throw invalid("不允许操作符号链接");
        }
        Path real = requireInsideRealPath(target);
        if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(real)) {
                if (ds.iterator().hasNext()) {
                    throw new IllegalStateException("目录不为空，请先删除里面的内容");
                }
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取目录失败");
            }
            try {
                Files.delete(real);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除文件夹失败");
            }
            log.info("共享资源删除目录: {}", toRelative(requireRoot(), real));
            return;
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound("不是文件或目录");
        }
        try {
            Files.delete(real);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除文件失败");
        }
        log.info("共享资源删除文件: {}", toRelative(requireRoot(), real));
    }

    /** 仅供测试：判断某相对路径在 shared 内是否存在（不跟随链接）。 */
    boolean existsForTest(String relativePath) {
        try {
            return Files.exists(requireRoot().resolve(relativePath), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
