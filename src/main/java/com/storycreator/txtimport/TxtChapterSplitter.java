package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TxtChapterSplitter {

    public record SplitChapter(int number, String title, String content, int wordCount) {}

    public List<SplitChapter> split(String text, List<ChapterSplitConfigEntity> configs) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Try each config in order; use first one that yields results
        for (ChapterSplitConfigEntity config : configs) {
            List<SplitChapter> result = splitWithConfig(text, config);
            if (!result.isEmpty()) {
                return result;
            }
        }

        // No config matched — return entire text as one chapter
        return List.of(new SplitChapter(1, "全文", text.trim(), text.trim().length()));
    }

    public List<SplitChapter> splitWithConfig(String text, ChapterSplitConfigEntity config) {
        Pattern pattern = Pattern.compile(config.getPattern());
        Matcher matcher = pattern.matcher(text);

        List<int[]> matchPositions = new ArrayList<>();
        List<String> matchTitles = new ArrayList<>();

        while (matcher.find()) {
            matchPositions.add(new int[]{matcher.start(), matcher.end()});
            String title = extractTitle(matcher, config.getTitleGroup());
            matchTitles.add(title);
        }

        if (matchPositions.isEmpty()) {
            return List.of();
        }

        List<SplitChapter> chapters = new ArrayList<>();
        for (int i = 0; i < matchPositions.size(); i++) {
            int contentStart;
            if (config.isIncludeMatch()) {
                contentStart = matchPositions.get(i)[0];
            } else {
                contentStart = matchPositions.get(i)[1];
            }

            int contentEnd;
            if (i + 1 < matchPositions.size()) {
                contentEnd = matchPositions.get(i + 1)[0];
            } else {
                contentEnd = text.length();
            }

            String content = text.substring(contentStart, contentEnd).trim();
            if (content.isEmpty()) continue;

            String title = matchTitles.get(i);
            if (title == null || title.isBlank()) {
                title = "第" + (chapters.size() + 1) + "章";
            }

            chapters.add(new SplitChapter(chapters.size() + 1, title.trim(), content, content.length()));
        }

        return chapters;
    }

    private String extractTitle(Matcher matcher, int titleGroup) {
        if (titleGroup < 0) {
            return null; // auto-number
        }
        try {
            if (titleGroup <= matcher.groupCount()) {
                String title = matcher.group(titleGroup);
                return title != null ? title.trim() : null;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
