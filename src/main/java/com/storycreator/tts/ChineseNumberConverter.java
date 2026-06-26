package com.storycreator.tts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for converting Arabic numbers and percentages to Chinese text.
 * Used by TTS to produce speakable Chinese representations of numeric values.
 */
public final class ChineseNumberConverter {

    private ChineseNumberConverter() {}

    static final String[] CHINESE_DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    static final Pattern PERCENTAGE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)%");
    static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * Replaces all percentage patterns (e.g. "95%") with Chinese text (e.g. "百分之九十五").
     * Must be called BEFORE convertNumberToChineseText to avoid interfering with the number in "95%".
     */
    public static String convertPercentageToChineseText(String text) {
        Matcher matcher = PERCENTAGE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String numberStr = matcher.group(1);
            String chinese = numberToChinese(numberStr);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("百分之" + chinese));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Replaces all standalone number patterns (e.g. "2024") with Chinese text (e.g. "二千零二十四").
     */
    public static String convertNumberToChineseText(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String numberStr = matcher.group();
            String chinese = numberToChinese(numberStr);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(chinese));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Converts a numeric string (integer or decimal) to Chinese text.
     */
    static String numberToChinese(String numberStr) {
        if (numberStr.contains(".")) {
            String[] parts = numberStr.split("\\.");
            String integerPart = integerToChinese(Long.parseLong(parts[0]));
            StringBuilder decimalPart = new StringBuilder();
            for (char c : parts[1].toCharArray()) {
                decimalPart.append(CHINESE_DIGITS[c - '0']);
            }
            return integerPart + "点" + decimalPart;
        } else {
            return integerToChinese(Long.parseLong(numberStr));
        }
    }

    /**
     * Converts a long integer to Chinese text representation.
     */
    static String integerToChinese(long num) {
        if (num == 0) return "零";
        if (num < 0) return "负" + integerToChinese(-num);

        StringBuilder sb = new StringBuilder();
        long yi = num / 100000000;
        long wan = (num % 100000000) / 10000;
        long qian = num % 10000;

        if (yi > 0) {
            sb.append(sectionToChinese((int) yi)).append("亿");
            if (wan == 0 && qian > 0) {
                sb.append("零");
            }
        }
        if (wan > 0) {
            if (yi > 0 && wan < 1000) {
                sb.append("零");
            }
            sb.append(sectionToChinese((int) wan)).append("万");
            if (qian > 0 && qian < 1000) {
                sb.append("零");
            }
        }
        if (qian > 0) {
            sb.append(sectionToChinese((int) qian));
        }

        return sb.toString();
    }

    private static String sectionToChinese(int num) {
        if (num == 0) return "";
        StringBuilder sb = new StringBuilder();
        int qian = num / 1000;
        int bai = (num % 1000) / 100;
        int shi = (num % 100) / 10;
        int ge = num % 10;

        if (qian > 0) {
            sb.append(CHINESE_DIGITS[qian]).append("千");
            if (bai == 0 && (shi > 0 || ge > 0)) sb.append("零");
        }
        if (bai > 0) {
            sb.append(CHINESE_DIGITS[bai]).append("百");
            if (shi == 0 && ge > 0) sb.append("零");
        }
        if (shi > 0) {
            sb.append(CHINESE_DIGITS[shi]).append("十");
        }
        if (ge > 0) {
            sb.append(CHINESE_DIGITS[ge]);
        }
        return sb.toString();
    }
}
