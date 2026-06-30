package com.storycreator.workflow.engine;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.port.ai.AiRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure static text processing utilities shared across workflow services.
 */
public final class TextProcessingUtils {

    private TextProcessingUtils() {}

    public static String stripAiFormatting(String content) {
        if (content == null) return null;
        return content.replace("`", "").replace("*", "");
    }

    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    public static String truncateNullable(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    public static String wrapContent(String content) {
        if (content == null || content.isBlank()) return "";
        return "\n" + content + "\n";
    }

    public static String cleanMarkdownForParsing(String text) {
        if (text == null) return null;
        String cleaned = text.replaceAll("\\*{1,3}", "");
        cleaned = cleaned.replaceAll("_{1,3}", "");
        cleaned = cleaned.replaceAll("(?m)^#{1,6}\\s*", "");
        cleaned = cleaned.replaceAll("(?<!^)(?<!\\n)【", "\n【");
        return cleaned;
    }

    public static String extractField(String text, String fieldName) {
        String cleaned = cleanMarkdownForParsing(text);
        if (cleaned == null) return null;
        Pattern pattern = Pattern.compile(
                "【" + fieldName + "】[：:]?\\s*(.+?)(?=\\n【|\\z)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            String result = matcher.group(1).trim();
            result = result.replaceAll("\\*+", "");
            return result;
        }
        return null;
    }

    public static void applyResolvedConfig(AiRequest request, AiProviderRouter.ResolvedModel resolved) {
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());
    }
}
