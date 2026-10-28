package com.storycreator.learn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把「单词匹配」现场打包成一个可直接导入微信开发者工具的小程序工程（zip）。
 *
 * <p><b>为什么在服务端打包、而不是往仓库里再存一份小程序源码：</b>
 * 词库（752 KB）是按后端切关算法算出来的，存第二份必然与网页漂移；页面代码则是相对稳定的。
 * 所以仓库里只保存<b>模板</b>（{@code src/main/resources/miniprogram-template/}，不含 data），
 * 导出时把模板 + 当前词库拼成完整工程。改词库不用同步小程序，改页面只需改模板一处。
 *
 * <p><b>模板文件从哪来：</b>{@code miniprogram-template.manifest.txt} 列出全部相对路径。
 * 刻意不扫描目录 —— 打进 fat jar 后目录枚举依赖具体的 classpath 实现，
 * 清单则在哪都能用，而且漏登记会被 {@code MiniprogramExporterTest#manifestMatchesTemplateDirectory} 直接报出来。
 *
 * <p>产出结构与 {@code scripts/learn/build_miniprogram_data.py} 逐字节一致（同样的压缩规则：
 * 去掉恒等的 key、{en,zh} 压成数组、按学段拆文件），差别只在文件头的注释。
 */
@Service
public class MiniprogramExporter {

    private static final Logger log = LoggerFactory.getLogger(MiniprogramExporter.class);

    /** 模板根目录（classpath）。 */
    static final String TEMPLATE_ROOT = "miniprogram-template/";

    /** 模板清单（classpath）。 */
    static final String TEMPLATE_MANIFEST = "miniprogram-template.manifest.txt";

    /** zip 内的顶层目录名，与 project.config.json 的 projectname 一致。 */
    static final String PACKAGE_DIR = "word-pair";

    /**
     * 读音资源根（classpath）。与网页端共用同一批整册 sprite：
     * {@code static/audio/word-match/<bookId>/sprite.m4a|sprite.json}。
     *
     * <p><b>为什么读音必须打成「一册一个分包」：</b>
     * 微信主包上限 2 MB，8 册音频合起来 7.3 MB，塞主包不可能；而
     * <b>主包不能使用分包资源</b>（开发者工具里能播、真机静默无声，官方社区已答复），
     * 所以「播音频的页面」必须和音频同处一个分包 ⇒ 每册复制一份玩法页进分包，
     * 主包的玩法页只负责识别「这册有读音」并 redirect 过去。
     */
    static final String AUDIO_ROOT = "static/audio/word-match/";

    /** 分包目录前缀（zip 与小程序内都是 packages/<bookId>/）。 */
    static final String PACK_SUBDIR = "packages";

    /** 学段 → 数据文件名后缀。ASCII，避免小程序 require 非 ASCII 模块名。 */
    /* 学段 → 导出文件名后缀。**必须与 `miniprogram-template/utils/bank.js` 的 LOADERS key 对得上**，
       也与 `scripts/learn/build_miniprogram_data.py` 的 STAGE_SLUG 同名。
       ⚠️ 2026-10-22 补上「专题」：此前漏了这一条，序列配对册被导出成 `levels-stage4.js`
       （fallback 分支），而小程序端 `require('../data/levels-topic.js')` 直接找不到模块
       ⇒ 这一册的关卡整册读不出来（网页端完全正常，所以肉眼发现不了）。 */
    private static final Map<String, String> STAGE_SLUG = Map.of(
            "小学", "primary",
            "初中", "junior",
            "高中", "senior",
            "专题", "topic",
            "大学", "college"
    );

    private static final String BOOKS_HEADER =
            "// 由 Story-Creator 导出（模板 + 现场切关），请勿手改。\n"
                    + "// 源：GET /learn/word-match/data + /learn/word-match/cet\n";

    private static final String LEVELS_HEADER =
            "// 由 Story-Creator 导出（模板 + 现场切关），请勿手改。\n"
                    + "// 关卡结构：[{ t: 主题, p: [[英文, 中文], ...] }]\n"
                    + "// 每对的 key 由运行时拼出：bookId + \":\" + levelIndex + \":\" + pairIndex\n";

    private final WordMatchBank wordMatchBank;
    private final CetWordBank cetWordBank;

    public MiniprogramExporter(WordMatchBank wordMatchBank, CetWordBank cetWordBank) {
        this.wordMatchBank = wordMatchBank;
        this.cetWordBank = cetWordBank;
    }

    /** 导出整个工程（含最新词库）。每次调用都重新生成，不缓存。 */
    public byte[] build() {
        List<WordMatchBank.BookInfo> books = allBooks();
        if (books.isEmpty()) {
            throw new IllegalStateException("没有加载到任何册次，无法导出小程序");
        }

        Set<String> audioBooks = audioBooks(books);
        Map<String, byte[]> dataFiles = buildDataFiles(books, audioBooks);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : dataFiles.entrySet()) {
                putEntry(zip, PACKAGE_DIR + "/data/" + e.getKey(), e.getValue());
            }
            int templateCount = 0;
            for (String relative : manifest()) {
                byte[] bytes = readTemplate(relative);
                /* 分包清单是算出来的（取决于哪些册有读音），不能写死在模板里 */
                if ("app.json".equals(relative)) {
                    bytes = injectSubpackages(bytes, audioBooks);
                }
                putEntry(zip, PACKAGE_DIR + "/" + relative, bytes);
                templateCount++;
            }
            for (String bookId : audioBooks) {
                writeAudioPack(zip, bookId);
            }
            log.info("导出小程序工程：{} 册 / {} 个数据文件 / {} 个模板文件 / {} 个读音分包（{}）",
                    books.size(), dataFiles.size(), templateCount, audioBooks.size(),
                    String.join(",", audioBooks));
        } catch (IOException e) {
            throw new IllegalStateException("导出小程序工程失败", e);
        }
        return buffer.toByteArray();
    }

    /** zip 内 data/ 下的文件（文件名 → 内容），顺序即写入顺序。 */
    private Map<String, byte[]> buildDataFiles(List<WordMatchBank.BookInfo> books, Set<String> audioBooks) {
        // 册元信息：只留小程序要用的字段，顺序与网页下发的一致
        List<Object> meta = new ArrayList<>();
        Set<String> stageOrder = new LinkedHashSet<>();
        Map<String, List<WordMatchBank.BookInfo>> byStage = new LinkedHashMap<>();
        for (WordMatchBank.BookInfo b : books) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", b.id());
            node.put("label", b.label());
            node.put("stage", b.stage());
            node.put("levelCount", b.levelCount());
            node.put("wordCount", b.wordCount());
            /* 只有真有读音才写这个字段：没有的册省掉它，books.js 不因「大多数册没有」而整体变胖。
               玩法页据此决定要不要 redirect 到该册的读音分包（见 play.js 的 _redirectToAudioPack）。 */
            if (audioBooks.contains(b.id())) {
                node.put("hasAudio", 1);
            }
            meta.add(node);

            stageOrder.add(b.stage());
            byStage.computeIfAbsent(b.stage(), k -> new ArrayList<>()).add(b);
        }

        List<Object> stages = new ArrayList<>();
        int index = 0;
        for (String stage : stageOrder) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", stage);
            node.put("slug", STAGE_SLUG.getOrDefault(stage, "stage" + index));
            node.put("count", byStage.get(stage).size());
            stages.add(node);
            index++;
        }

        Map<String, byte[]> out = new LinkedHashMap<>();
        // 两个顶层键分行写：books.js 很小（约 3KB），分行后人工排查时能一眼看清册次与学段
        out.put("books.js", utf8(BOOKS_HEADER
                + "module.exports = {\n"
                + "  books: " + json(meta) + ",\n"
                + "  stages: " + json(stages) + "\n"
                + "};\n"));

        for (Map.Entry<String, List<WordMatchBank.BookInfo>> e : byStage.entrySet()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (WordMatchBank.BookInfo book : e.getValue()) {
                List<Object> levels = new ArrayList<>();
                for (WordMatchBank.Level level : levelsOf(book.id())) {
                    List<Object> pairs = new ArrayList<>();
                    for (WordMatchBank.WordPair pair : level.pairs()) {
                        pairs.add(List.of(pair.en(), pair.zh()));
                    }
                    Map<String, Object> lv = new LinkedHashMap<>();
                    lv.put("t", level.theme());
                    lv.put("p", pairs);
                    levels.add(lv);
                }
                payload.put(book.id(), levels);
            }
            String slug = STAGE_SLUG.getOrDefault(e.getKey(), "stage" + out.size());
            out.put("levels-" + slug + ".js", utf8(LEVELS_HEADER + "module.exports = " + json(payload) + ";\n"));
        }
        return out;
    }

    private List<WordMatchBank.BookInfo> allBooks() {
        List<WordMatchBank.BookInfo> all = new ArrayList<>(wordMatchBank.getBooks());
        all.addAll(cetWordBank.getBooks());
        return all;
    }

    /** 两套词源（人教版 / 大学）各自持有自己的关卡，按册 id 决定去问谁。 */
    private List<WordMatchBank.Level> levelsOf(String bookId) {
        List<WordMatchBank.Level> levels = wordMatchBank.getLevels(bookId);
        return levels.isEmpty() ? cetWordBank.getLevels(bookId) : levels;
    }

    /** 哪些册已经生成了读音（sprite.m4a 与 sprite.json 都在 classpath 里才算）。 */
    private static Set<String> audioBooks(List<WordMatchBank.BookInfo> books) {
        Set<String> out = new LinkedHashSet<>();
        for (WordMatchBank.BookInfo b : books) {
            if (hasAudio(b.id())) {
                out.add(b.id());
            }
        }
        return out;
    }

    private static boolean hasAudio(String bookId) {
        return exists(AUDIO_ROOT + bookId + "/sprite.m4a")
                && exists(AUDIO_ROOT + bookId + "/sprite.json");
    }

    private static boolean exists(String classpathPath) {
        return new ClassPathResource(classpathPath).exists();
    }

    /**
     * 把 {@code subpackages} 注入 app.json。
     *
     * <p>不整份生成 app.json：窗口样式、lazyCodeLoading 这些是页面配置，留在模板里才是真相；
     * 这里只补「哪些册有读音分包」这一项 —— 它是按音频资源算出来的，模板不可能写死。
     */
    private static byte[] injectSubpackages(byte[] appJson, Set<String> audioBooks) {
        if (audioBooks.isEmpty()) {
            return appJson;
        }
        String text = new String(appJson, StandardCharsets.UTF_8);
        int at = text.lastIndexOf('}');
        if (at < 0) {
            throw new IllegalStateException("app.json 结构异常：找不到收尾的 }");
        }
        List<Object> subs = new ArrayList<>();
        for (String bookId : audioBooks) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("root", PACK_SUBDIR + "/" + bookId);
            node.put("pages", List.of("play/play"));
            subs.add(node);
        }
        /* 保留原文件的缩进风格：只去掉收尾前的空白，再接一项。 */
        String head = text.substring(0, at).stripTrailing();
        return utf8(head + ",\n  \"subpackages\": " + json(subs) + "\n}\n");
    }

    /**
     * 一册一个读音分包：玩法页 + 读音索引 + 整册 sprite。
     *
     * <p>体积核对（AAC 32 kbps）：单册 539 KB ~ 1392 KB，加玩法页约 50 KB，
     * 全部 &lt; 微信单分包 2 MB 上限；8 册合计 7.3 MB，主包 1.0 MB，总包远低于 20 MB。
     */
    private static void writeAudioPack(ZipOutputStream zip, String bookId) throws IOException {
        String root = PACKAGE_DIR + "/" + PACK_SUBDIR + "/" + bookId + "/";
        putEntry(zip, root + "play/play.js", packPlayJs(bookId));
        for (String name : new String[]{"play/play.wxml", "play/play.json", "play/play.wxss"}) {
            putEntry(zip, root + name, readTemplate("pages/" + name));
        }
        putEntry(zip, root + "audio/index.js", audioIndexModule(bookId));
        putEntry(zip, root + "audio/sprite.m4a", readAudio(bookId, "sprite.m4a"));
    }

    /**
     * 分包里的玩法页 = 模板玩法页 + 两处改动：
     * ① {@code require} 路径下移一层（{@code packages/<id>/play/} 比 {@code pages/play/} 深一层）；
     * ② 末尾给 {@code speechBoot} 赋值，把该册的读音索引交给 utils/speech.js。
     * 赋值（而非直接调用 configure）是为了让页面每次 onLoad 都能重新 configure ——
     * 小程序模块只执行一次，直接写在顶层只有首次进页有效。
     *
     * <p>把同一份页面代码复制进每个分包，是因为微信不允许主包使用分包资源；
     * 复制由导出器完成，人只维护模板一份。
     */
    private static byte[] packPlayJs(String bookId) {
        String js = new String(readTemplate("pages/play/play.js"), StandardCharsets.UTF_8);
        String moved = js.replace("require('../../utils/", "require('../../../utils/");
        if (moved.equals(js)) {
            throw new IllegalStateException(
                    "play.js 里没有 require('../../utils/...')，分包页改写路径会失败（运行时报模块找不到）");
        }
        String trailer = "\n\n/* ===== 以下由 MiniprogramExporter 追加：本页在读音分包里 ====="
                + "\n   模板里没有这段。它把整册读音索引交给 utils/speech.js，"
                + "\n   于是同一个玩法页在主包是「纯配对」、在分包里多一个点读能力。 */"
                /* ⚠️ 只给 speechBoot 赋值，**不在模块顶层直接 configure**：
                   小程序模块只执行一次，第二次进入同一册的分包页不会再跑顶层代码，
                   而那时 speech 的配置可能已被别的册覆盖 ⇒ 点上去没声音（真机踩过）。
                   模板的 onLoad 每次都会调用 speechBoot，所以 configure 也就每次都会重新执行。 */
                + "\nvar __speechIndex = require('../audio/index.js');"
                /* ⚠️ 是 ../audio 而不是 ./audio：本文件在 <root>/play/ 里，音频在同级的 <root>/audio/。
                   写成 ./audio 会被解析成 <root>/play/audio/index.js —— 真机上整个分包页白屏（模块找不到），
                   而开发者工具里未必立刻报出来。 */
                + "\nspeechBoot = function () {"
                + "\n    require('../../../utils/speech.js')"
                + "\n        .configure('" + PACK_SUBDIR + "/" + bookId + "', __speechIndex);"
                + "\n};\n";
        return utf8(moved + trailer);
    }

    /** sprite.json → 可直接 require 的 js 模块（省一次文件读取，也避开部分基础库解析 json 的兼容问题）。 */
    private static byte[] audioIndexModule(String bookId) {
        String text = new String(readAudio(bookId, "sprite.json"), StandardCharsets.UTF_8).strip();
        return utf8("// 由 Story-Creator 导出（整册读音索引），请勿手改。\n"
                + "// 结构：{ sampleRate, headMs, tailMs, duration, words: [[词, 起点秒, 终点秒], ...] }\n"
                + "module.exports = " + text + ";\n");
    }

    private static byte[] readAudio(String bookId, String name) {
        ClassPathResource resource = new ClassPathResource(AUDIO_ROOT + bookId + "/" + name);
        if (!resource.exists()) {
            throw new IllegalStateException("读音资源缺失：" + AUDIO_ROOT + bookId + "/" + name);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取读音资源失败：" + bookId + "/" + name, e);
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] readTemplate(String relative) {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_ROOT + relative);
        if (!resource.exists()) {
            throw new IllegalStateException("模板文件缺失：" + TEMPLATE_ROOT + relative);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取模板文件失败：" + relative, e);
        }
    }

    private static List<String> manifest() {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_MANIFEST);
        List<String> paths = new ArrayList<>();
        try (InputStream in = resource.getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    paths.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取模板清单失败", e);
        }
        if (paths.isEmpty()) {
            throw new IllegalStateException("模板清单是空的：" + TEMPLATE_MANIFEST);
        }
        return paths;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 紧凑 JSON（与 Python {@code json.dumps(separators=(',', ':'), ensure_ascii=False)} 同形）。
     * 不用 Jackson 是因为它的默认转义策略、Map 顺序处理都与这里要的字节级一致不同。
     */
    static String json(Object value) {
        StringBuilder sb = new StringBuilder(1 << 16);
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Integer i) {
            sb.append(i.intValue());
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeJson(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型：" + value.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
