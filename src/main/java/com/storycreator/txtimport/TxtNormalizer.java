package com.storycreator.txtimport;

/**
 * TXT 正文预处理工具：在做正则匹配之前把「非标准空白字符」归一化为半角空格。
 *
 * <p>背景：中文小说 TXT 的章节标题常用全角空格（U+3000）做缩进，例如
 * {@code "　第104章 标题"}。而 Java 正则的 {@code \s} 默认只等价于
 * {@code [ \t\n\x0B\f\r]}，<b>不包含 U+3000</b>，于是带全角缩进的标题行在
 * {@code (?m)^\s*第...章} 这类锚定行首的正则下直接匹配失败，整本书被漏切。</p>
 *
 * <p>因此在进入 {@link TxtChapterSplitter} 之前先做一次归一化，把全角空格（以及
 * 网页粘贴常见的 NBSP 等）统一替换成普通空格，使内置与用户自定义的所有
 * {@code \s} / {@code [ \t]} 写法都能正常工作。</p>
 *
 * <p><b>长度保持</b>：所有被替换字符都是「单一空白字符」，替换后仍是一个半角空格，
 * 因此字符串长度与字符下标与原文一一对应（1:1 且不增不减）——这一点很关键，
 * 正则匹配得到的 start/end 偏移量可以直接用于原文切片，不会出现错位。</p>
 */
public final class TxtNormalizer {

    /**
     * 需归一化为半角空格的空白字符：
     * U+3000 全角空格（中文排版缩进）、U+00A0 不换行空格、U+2007 数字空格、U+202F 窄不换行空格。
     */
    private static final char[] WIDE_SPACES = {'\u3000', '\u00A0', '\u2007', '\u202F'};

    private TxtNormalizer() {
    }

    /** 判断字符是否为需要归一化的「宽空格」。 */
    public static boolean isWideSpace(char c) {
        for (char w : WIDE_SPACES) {
            if (c == w) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把全文中的宽空格替换为半角空格（长度保持）。
     *
     * @param text 原始正文
     * @return 归一化后的正文；无需替换时返回原对象本身（避免无谓拷贝）
     */
    public static String normalizeSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean dirty = false;
        for (int i = 0; i < text.length(); i++) {
            if (isWideSpace(text.charAt(i))) {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (isWideSpace(chars[i])) {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }
}
