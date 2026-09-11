package com.storycreator.txtimport;

/**
 * 一次 TXT 分割得到的单章结果。
 */
public record SplitChapter(int number, String title, String content, int wordCount) {
}
