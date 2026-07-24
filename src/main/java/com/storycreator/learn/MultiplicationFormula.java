package com.storycreator.learn;

import java.util.List;

public record MultiplicationFormula(
    String itemKey,
    int a,
    int b,
    int result,
    String chineseText,
    String mathExpression
) {
    public static final String MODULE = "multiplication_table";

    /** Prefix announcements for each group (e.g., "一的乘法口诀") */
    public static final List<PrefixEntry> PREFIXES = List.of(
        new PrefixEntry("prefix_1", "一的乘法口诀"),
        new PrefixEntry("prefix_2", "二的乘法口诀"),
        new PrefixEntry("prefix_3", "三的乘法口诀"),
        new PrefixEntry("prefix_4", "四的乘法口诀"),
        new PrefixEntry("prefix_5", "五的乘法口诀"),
        new PrefixEntry("prefix_6", "六的乘法口诀"),
        new PrefixEntry("prefix_7", "七的乘法口诀"),
        new PrefixEntry("prefix_8", "八的乘法口诀"),
        new PrefixEntry("prefix_9", "九的乘法口诀")
    );

    public record PrefixEntry(String itemKey, String chineseText) {}

    public static final List<MultiplicationFormula> FORMULAS = List.of(
        new MultiplicationFormula("1x1", 1, 1, 1, "一一得一", "1×1=1"),
        new MultiplicationFormula("1x2", 1, 2, 2, "一二得二", "1×2=2"),
        new MultiplicationFormula("1x3", 1, 3, 3, "一三得三", "1×3=3"),
        new MultiplicationFormula("1x4", 1, 4, 4, "一四得四", "1×4=4"),
        new MultiplicationFormula("1x5", 1, 5, 5, "一五得五", "1×5=5"),
        new MultiplicationFormula("1x6", 1, 6, 6, "一六得六", "1×6=6"),
        new MultiplicationFormula("1x7", 1, 7, 7, "一七得七", "1×7=7"),
        new MultiplicationFormula("1x8", 1, 8, 8, "一八得八", "1×8=8"),
        new MultiplicationFormula("1x9", 1, 9, 9, "一九得九", "1×9=9"),
        new MultiplicationFormula("2x2", 2, 2, 4, "二二得四", "2×2=4"),
        new MultiplicationFormula("2x3", 2, 3, 6, "二三得六", "2×3=6"),
        new MultiplicationFormula("2x4", 2, 4, 8, "二四得八", "2×4=8"),
        new MultiplicationFormula("2x5", 2, 5, 10, "二五一十", "2×5=10"),
        new MultiplicationFormula("2x6", 2, 6, 12, "二六十二", "2×6=12"),
        new MultiplicationFormula("2x7", 2, 7, 14, "二七十四", "2×7=14"),
        new MultiplicationFormula("2x8", 2, 8, 16, "二八十六", "2×8=16"),
        new MultiplicationFormula("2x9", 2, 9, 18, "二九十八", "2×9=18"),
        new MultiplicationFormula("3x3", 3, 3, 9, "三三得九", "3×3=9"),
        new MultiplicationFormula("3x4", 3, 4, 12, "三四十二", "3×4=12"),
        new MultiplicationFormula("3x5", 3, 5, 15, "三五十五", "3×5=15"),
        new MultiplicationFormula("3x6", 3, 6, 18, "三六十八", "3×6=18"),
        new MultiplicationFormula("3x7", 3, 7, 21, "三七二十一", "3×7=21"),
        new MultiplicationFormula("3x8", 3, 8, 24, "三八二十四", "3×8=24"),
        new MultiplicationFormula("3x9", 3, 9, 27, "三九二十七", "3×9=27"),
        new MultiplicationFormula("4x4", 4, 4, 16, "四四十六", "4×4=16"),
        new MultiplicationFormula("4x5", 4, 5, 20, "四五二十", "4×5=20"),
        new MultiplicationFormula("4x6", 4, 6, 24, "四六二十四", "4×6=24"),
        new MultiplicationFormula("4x7", 4, 7, 28, "四七二十八", "4×7=28"),
        new MultiplicationFormula("4x8", 4, 8, 32, "四八三十二", "4×8=32"),
        new MultiplicationFormula("4x9", 4, 9, 36, "四九三十六", "4×9=36"),
        new MultiplicationFormula("5x5", 5, 5, 25, "五五二十五", "5×5=25"),
        new MultiplicationFormula("5x6", 5, 6, 30, "五六三十", "5×6=30"),
        new MultiplicationFormula("5x7", 5, 7, 35, "五七三十五", "5×7=35"),
        new MultiplicationFormula("5x8", 5, 8, 40, "五八四十", "5×8=40"),
        new MultiplicationFormula("5x9", 5, 9, 45, "五九四十五", "5×9=45"),
        new MultiplicationFormula("6x6", 6, 6, 36, "六六三十六", "6×6=36"),
        new MultiplicationFormula("6x7", 6, 7, 42, "六七四十二", "6×7=42"),
        new MultiplicationFormula("6x8", 6, 8, 48, "六八四十八", "6×8=48"),
        new MultiplicationFormula("6x9", 6, 9, 54, "六九五十四", "6×9=54"),
        new MultiplicationFormula("7x7", 7, 7, 49, "七七四十九", "7×7=49"),
        new MultiplicationFormula("7x8", 7, 8, 56, "七八五十六", "7×8=56"),
        new MultiplicationFormula("7x9", 7, 9, 63, "七九六十三", "7×9=63"),
        new MultiplicationFormula("8x8", 8, 8, 64, "八八六十四", "8×8=64"),
        new MultiplicationFormula("8x9", 8, 9, 72, "八九七十二", "8×9=72"),
        new MultiplicationFormula("9x9", 9, 9, 81, "九九八十一", "9×9=81")
    );

    /** Total audio entries: 9 prefixes + 45 formulas */
    public static final int TOTAL_AUDIO_COUNT = PREFIXES.size() + FORMULAS.size();

    public static MultiplicationFormula findByKey(String itemKey) {
        return FORMULAS.stream()
            .filter(f -> f.itemKey().equals(itemKey))
            .findFirst()
            .orElse(null);
    }
}
