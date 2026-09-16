package com.storycreator.txtimport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 TXT 文件名自动解析「项目标题」与「作者」。
 *
 * <p>解析约定（与前端 {@code txt-import.html} 的 {@code parseFilename} 保持完全一致）：
 * <ul>
 *   <li><b>项目标题</b>：取书名号 {@code 《》} 包裹的内容；若没有书名号，则取中文方括号 {@code 【】}
 *       包裹的内容（如 {@code 【我的测试人员】 1-147 ... 作者：散人.txt} → {@code 我的测试人员}）；
 *       两者都没有时，回退为去掉扩展名的文件名。</li>
 *   <li><b>作者</b>：取 {@code 作者：} 或 {@code 作者:} 之后的连续字符，遇到特殊符号（括号、空白、常见中英文标点）即结束；
 *       标题里的 {@code 作者：} 不会被误当作作者标记（只在标题括号之后搜索：有书名号在书名号之后，
 *       否则若有方括号标题则在 {@code 】} 之后）。</li>
 * </ul>
 */
public final class TxtFilenameParser {

    private static final Pattern TITLE = Pattern.compile("《([^》]+)》");
    /** 中文方括号标题模式（次优先级，用于「【书名】 ... 作者：xx」命名习惯）。 */
    private static final Pattern TITLE_BRACKET = Pattern.compile("【([^】]+)】");
    private static final Pattern AUTHOR = Pattern.compile("作者[:：]\\s*([^【】\\[\\]（）()\\s、，,。；;]+)");

    private TxtFilenameParser() {
    }

    /**
     * 解析项目标题。
     *
     * @param fileName 原始文件名（可含扩展名）；为空时回退为「未命名」
     * @return 解析得到的标题（已 trim）
     */
    public static String parseTitle(String fileName) {
        String base = stripExtension(fileName);
        if (base == null || base.isBlank()) {
            return "未命名";
        }
        Matcher m = TITLE.matcher(base);
        if (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        // 次优先级：中文方括号【】标题
        m = TITLE_BRACKET.matcher(base);
        if (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return base.trim();
    }

    /**
     * 解析作者。
     *
     * @param fileName 原始文件名（可含扩展名）
     * @return 解析得到的作者；文件名中不含作者标记时返回 {@code null}
     */
    public static String parseAuthor(String fileName) {
        String base = stripExtension(fileName);
        if (base == null || base.isBlank()) {
            return null;
        }
        // 只在标题括号之后搜索作者标记，避免把标题里的「作者：」误判为作者：
        // 有书名号时从《》之后搜；否则有方括号标题时从】之后搜；都没有时搜全串
        String searchIn = base;
        int close = base.indexOf('》');
        if (close >= 0) {
            searchIn = base.substring(close);
        } else {
            close = base.indexOf('】');
            if (close >= 0) {
                searchIn = base.substring(close);
            }
        }
        Matcher m = AUTHOR.matcher(searchIn);
        if (m.find()) {
            String a = m.group(1).trim();
            return a.isEmpty() ? null : a;
        }
        return null;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
