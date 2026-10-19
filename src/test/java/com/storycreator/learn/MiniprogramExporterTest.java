package com.storycreator.learn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「导出小程序源码」的回归测试。
 *
 * <p>这个功能的价值在于<b>仓库里不再保存第二份小程序源码</b>：模板（{@code miniprogram-template/}）
 * 只有页面代码，词库在导出时由 {@link WordMatchBank} / {@link CetWordBank} 现算。
 * 所以测试要守住三件事：
 * <ol>
 *   <li><b>清单不许漏</b>：新增模板文件却忘了登记，导出的包会缺文件，而这种缺失在浏览器里完全看不出来；</li>
 *   <li><b>数据不许空</b>：册数 / 关卡数 / 词条数与网页版一致（26 册 / 3546 关 / 20191 词）；</li>
 *   <li><b>工程要能直接跑</b>：appid 留空、页面标题正确、每个关卡都是「主题 + 二元组数组」。</li>
 * </ol>
 */
class MiniprogramExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MiniprogramExporter exporter =
            new MiniprogramExporter(new WordMatchBank(), new CetWordBank());

    @Test
    @DisplayName("模板清单与磁盘文件一一对应（新增/删除模板文件必须同步清单）")
    void manifestMatchesTemplateDirectory() throws IOException, URISyntaxException {
        Path templateDir = Path.of(MiniprogramExporter.class.getClassLoader()
                .getResource("miniprogram-template").toURI());
        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(templateDir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> onDisk.add(templateDir.relativize(p).toString().replace('\\', '/')));
        }

        List<String> listed = new ArrayList<>();
        for (String line : new String(MiniprogramExporter.class.getClassLoader()
                .getResourceAsStream(MiniprogramExporter.TEMPLATE_MANIFEST).readAllBytes(),
                StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                listed.add(trimmed);
            }
        }

        assertThat(onDisk).as("模板目录里的文件").isNotEmpty();
        assertThat(listed).as("清单里的条目")
                .containsExactlyInAnyOrderElementsOf(onDisk);
    }

    @Test
    @DisplayName("导出的包里 data/ 与模板文件齐全，且全部落在 word-pair/ 下")
    void zipContainsDataAndTemplate() {
        Map<String, byte[]> entries = unzip(exporter.build());

        assertThat(entries.keySet()).allMatch(name -> name.startsWith("word-pair/"));
        assertThat(entries).containsKeys(
                "word-pair/data/books.js",
                "word-pair/data/levels-primary.js",
                "word-pair/data/levels-junior.js",
                "word-pair/data/levels-senior.js",
                "word-pair/data/levels-college.js",
                "word-pair/app.js",
                "word-pair/app.json",
                "word-pair/project.config.json",
                "word-pair/pages/index/index.wxml",
                "word-pair/pages/play/play.wxml",
                "word-pair/utils/bank.js",
                "word-pair/README.md");
        // 5 个数据文件 + 模板清单里的全部文件
        assertThat(entries).hasSize(5 + manifestSize());
    }

    @Test
    @DisplayName("books.js：26 册 / 4 个学段 / 3546 关 / 20191 词，与网页版一致")
    void booksJsMatchesWebData() throws IOException {
        Map<String, byte[]> entries = unzip(exporter.build());
        JsonNode root = moduleJson(entries.get("word-pair/data/books.js"));

        JsonNode books = root.path("books");
        assertThat(books.size()).as("册数").isEqualTo(26);

        int levels = 0;
        int words = 0;
        for (JsonNode book : books) {
            levels += book.path("levelCount").asInt();
            words += book.path("wordCount").asInt();
            assertThat(book.path("id").asText()).isNotBlank();
            assertThat(book.path("label").asText()).isNotBlank();
        }
        assertThat(levels).as("关卡总数").isEqualTo(3546);
        assertThat(words).as("词条总数").isEqualTo(20191);

        JsonNode stages = root.path("stages");
        assertThat(stages.size()).isEqualTo(4);
        List<String> slugs = new ArrayList<>();
        stages.forEach(s -> slugs.add(s.path("slug").asText()));
        assertThat(slugs).containsExactly("primary", "junior", "senior", "college");
    }

    @Test
    @DisplayName("levels-*.js：每关都是「主题 + 二元组数组」，且关卡数与 books.js 对得上")
    void levelsFilesAreConsistent() throws IOException {
        Map<String, byte[]> entries = unzip(exporter.build());
        JsonNode books = moduleJson(entries.get("word-pair/data/books.js")).path("books");

        Map<String, Integer> expectedBySlug = new LinkedHashMap<>();
        for (JsonNode book : books) {
            String stage = book.path("stage").asText();
            String slug = switch (stage) {
                case "小学" -> "primary";
                case "初中" -> "junior";
                case "高中" -> "senior";
                case "大学" -> "college";
                default -> throw new IllegalStateException("未知学段：" + stage);
            };
            expectedBySlug.merge(slug, book.path("levelCount").asInt(), Integer::sum);
        }

        for (Map.Entry<String, Integer> e : expectedBySlug.entrySet()) {
            JsonNode payload = moduleJson(entries.get("word-pair/data/levels-" + e.getKey() + ".js"));
            int levels = 0;
            var fields = payload.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> bookNode = fields.next();
                for (JsonNode level : bookNode.getValue()) {
                    levels++;
                    assertThat(level.path("t").isTextual()).as(bookNode.getKey() + " 的主题字段").isTrue();
                    JsonNode pairs = level.path("p");
                    assertThat(pairs.size()).as(bookNode.getKey() + " 每关对数").isBetween(3, 7);
                    for (JsonNode pair : pairs) {
                        assertThat(pair.size()).as("单词对必须是 [英文, 中文]").isEqualTo(2);
                        assertThat(pair.get(0).asText()).isNotBlank();
                        assertThat(pair.get(1).asText()).isNotBlank();
                    }
                }
            }
            assertThat(levels).as(e.getKey() + " 学段的关卡数").isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("工程配置：appid 留空、标题与 projectname 正确")
    void projectConfigIsReadyToImport() throws IOException {
        Map<String, byte[]> entries = unzip(exporter.build());

        JsonNode appJson = MAPPER.readTree(text(entries.get("word-pair/app.json")));
        assertThat(appJson.path("window").path("navigationBarTitleText").asText()).isEqualTo("单词趣味配对");
        assertThat(appJson.path("pages")).hasSize(2);

        JsonNode project = MAPPER.readTree(text(entries.get("word-pair/project.config.json")));
        assertThat(project.path("appid").asText())
                .as("导出的包不能带别人的 AppID，留空由使用者填")
                .isEmpty();
        assertThat(project.path("projectname").asText()).isEqualTo("word-pair");
    }

    @Test
    @DisplayName("导出的 README 交代了 appid 与重新导出的口径")
    void readmeExplainsHowToRun() {
        Map<String, byte[]> entries = unzip(exporter.build());
        String readme = text(entries.get("word-pair/README.md"));
        assertThat(readme).contains("appid").contains("微信开发者工具").contains("miniprogram-template");
    }

    // ==================== 工具 ====================

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return entries;
    }

    /**
     * 数据文件是 {@code module.exports = ...;} —— 去掉外壳后按 JSON 解析。
     * books.js 的两个顶层键按 JS 习惯写成不带引号的 {@code books: / stages:}（只有这两个，
     * 为了人工排查时好读），这里补上引号；levels-*.js 本来就是纯 JSON。
     */
    private static JsonNode moduleJson(byte[] js) throws IOException {
        String body = text(js);
        // 从 module.exports 之后找起：文件头的注释里也写了 { t: ..., p: ... } 的形状示例
        int start = body.indexOf("module.exports");
        assertThat(start).as("数据文件里没有 module.exports").isGreaterThanOrEqualTo(0);
        start = body.indexOf('{', start);
        int end = body.lastIndexOf('}');
        assertThat(start).as("数据文件里找不到 JSON 体").isGreaterThanOrEqualTo(0);
        String json = body.substring(start, end + 1)
                .replace("\n  books:", "\n  \"books\":")
                .replace("\n  stages:", "\n  \"stages\":");
        return MAPPER.readTree(json);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int manifestSize() {
        try (var in = MiniprogramExporter.class.getClassLoader()
                .getResourceAsStream(MiniprogramExporter.TEMPLATE_MANIFEST)) {
            int count = 0;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
