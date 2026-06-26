package com.storycreator.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChineseNumberConverterTest {

    @ParameterizedTest
    @CsvSource({
            "0, 零",
            "5, 五",
            "10, 一十",
            "25, 二十五",
            "100, 一百",
            "105, 一百零五",
            "110, 一百一十",
            "1000, 一千",
            "1001, 一千零一",
            "1010, 一千零一十",
            "1100, 一千一百",
            "10000, 一万",
            "10001, 一万零一",
            "12345, 一万二千三百四十五",
            "100000000, 一亿",
            "100000001, 一亿零一",
            "2024, 二千零二十四"
    })
    void numberToChinese_integers(String input, String expected) {
        assertEquals(expected, ChineseNumberConverter.numberToChinese(input));
    }

    @Test
    void numberToChinese_decimal() {
        assertEquals("三点一四", ChineseNumberConverter.numberToChinese("3.14"));
        assertEquals("零点五", ChineseNumberConverter.numberToChinese("0.5"));
        assertEquals("一百点零九", ChineseNumberConverter.numberToChinese("100.09"));
    }

    @Test
    void integerToChinese_negative() {
        assertEquals("负五", ChineseNumberConverter.integerToChinese(-5));
        assertEquals("负一百", ChineseNumberConverter.integerToChinese(-100));
    }

    @Test
    void convertPercentageToChineseText_simplePercentage() {
        assertEquals("百分之九十五", ChineseNumberConverter.convertPercentageToChineseText("95%"));
    }

    @Test
    void convertPercentageToChineseText_decimalPercentage() {
        assertEquals("百分之三点五", ChineseNumberConverter.convertPercentageToChineseText("3.5%"));
    }

    @Test
    void convertPercentageToChineseText_embeddedInText() {
        assertEquals("成功率百分之九十五以上",
                ChineseNumberConverter.convertPercentageToChineseText("成功率95%以上"));
    }

    @Test
    void convertPercentageToChineseText_multiplePercentages() {
        assertEquals("从百分之三十提升到百分之八十",
                ChineseNumberConverter.convertPercentageToChineseText("从30%提升到80%"));
    }

    @Test
    void convertNumberToChineseText_chapterNumber() {
        assertEquals("第三章", ChineseNumberConverter.convertNumberToChineseText("第3章"));
    }

    @Test
    void convertNumberToChineseText_year() {
        assertEquals("二千零二十四年", ChineseNumberConverter.convertNumberToChineseText("2024年"));
    }

    @Test
    void convertNumberToChineseText_multipleNumbers() {
        assertEquals("第一章到第一十章",
                ChineseNumberConverter.convertNumberToChineseText("第1章到第10章"));
    }

    @Test
    void convertNumberToChineseText_noNumbers() {
        assertEquals("没有数字", ChineseNumberConverter.convertNumberToChineseText("没有数字"));
    }

    @Test
    void executionOrder_percentageThenNumber() {
        // Simulates the order in TtsService.cleanText: percentage first, then number
        String input = "成功率95%，共100人参加";
        String afterPercentage = ChineseNumberConverter.convertPercentageToChineseText(input);
        String afterNumber = ChineseNumberConverter.convertNumberToChineseText(afterPercentage);
        assertEquals("成功率百分之九十五，共一百人参加", afterNumber);
    }

    @Test
    void executionOrder_percentageAlone() {
        // "95%" should become "百分之九十五" after both steps, not "百分之九十五%"
        String input = "95%";
        String afterPercentage = ChineseNumberConverter.convertPercentageToChineseText(input);
        String afterNumber = ChineseNumberConverter.convertNumberToChineseText(afterPercentage);
        assertEquals("百分之九十五", afterNumber);
    }
}
