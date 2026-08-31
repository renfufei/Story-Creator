package com.storycreator.workflow.engine;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.port.ai.AiRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure static text processing utilities shared across workflow services.
 */
public final class TextProcessingUtils {

    private TextProcessingUtils() {}

    // Known character card field names used as headers
    private static final java.util.Set<String> KNOWN_FIELDS = java.util.Set.of(
            "姓名", "性别", "年龄", "身份", "性格", "外貌", "背景", "动机", "能力", "关系", "概要",
            "角色名", "身份/职业", "性格特点", "外貌特征", "叙事功能", "角色类型"
    );

    // Matches a field header line: 【fieldName】 or fieldName：/fieldName:
    private static final Pattern FIELD_HEADER_PATTERN = Pattern.compile(
            "^(?:【(.+?)】|([\\u4e00-\\u9fff/]{1,6})[：:])(.*)$");

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
        // Insert newline before 【 only when it's a known field header (not content like 依托【还原符】)
        StringBuilder sb = new StringBuilder();
        Pattern inlineBracket = Pattern.compile("(?<!^)(?<!\\n)【(.+?)】");
        Matcher m = inlineBracket.matcher(cleaned);
        int lastEnd = 0;
        while (m.find()) {
            String name = m.group(1);
            if (KNOWN_FIELDS.contains(name)) {
                sb.append(cleaned, lastEnd, m.start());
                sb.append("\n");
                sb.append(m.group());
                lastEnd = m.end();
            }
        }
        if (lastEnd > 0) {
            sb.append(cleaned, lastEnd, cleaned.length());
            cleaned = sb.toString();
        }
        return cleaned;
    }

    /**
     * Parse all fields from text into a map using line-by-line merge strategy.
     * Lines that don't start with a recognized field header are merged into the previous field.
     * A bracket header 【xxx】 is only recognized if it's a known field name OR the line starts
     * with the bracket (not embedded in content like "依托【还原符】").
     */
    public static Map<String, String> parseAllFields(String text) {
        String cleaned = cleanMarkdownForParsing(text);
        if (cleaned == null) return Map.of();

        Map<String, String> fields = new LinkedHashMap<>();
        String currentField = null;
        StringBuilder currentValue = new StringBuilder();

        for (String line : cleaned.split("\\R")) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;

            Matcher headerMatcher = FIELD_HEADER_PATTERN.matcher(trimmedLine);
            if (headerMatcher.matches() && isRealFieldHeader(headerMatcher)) {
                // Save previous field
                if (currentField != null) {
                    fields.put(currentField, currentValue.toString().trim());
                }
                // Start new field
                String bracketName = headerMatcher.group(1);
                String colonName = headerMatcher.group(2);
                currentField = bracketName != null ? bracketName : colonName;
                String restOfLine = headerMatcher.group(3);
                currentValue = new StringBuilder();
                if (restOfLine != null && !restOfLine.isBlank()) {
                    currentValue.append(restOfLine.replaceFirst("^[：:]?\\s*", "").trim());
                }
            } else if (currentField != null) {
                // Continuation line — merge into current field
                if (currentValue.length() > 0) {
                    currentValue.append("\n");
                }
                currentValue.append(trimmedLine);
            }
        }
        // Save last field
        if (currentField != null) {
            fields.put(currentField, currentValue.toString().trim());
        }
        return fields;
    }

    /**
     * Determines if a header match is a real field header vs content that happens to contain 【】.
     * A bracket name must be a known field, OR the rest of the line after 】 must be short enough
     * to be a field value start (not a sentence fragment like "依托【还原符】，可随时...").
     */
    private static boolean isRealFieldHeader(Matcher headerMatcher) {
        String bracketName = headerMatcher.group(1);
        String colonName = headerMatcher.group(2);
        // Colon format is always considered a field header (line starts with CJK label + colon)
        if (colonName != null) return true;
        // Bracket format: must be a known field name
        return bracketName != null && KNOWN_FIELDS.contains(bracketName);
    }

    public static String extractField(String text, String fieldName) {
        if (text == null) return null;
        Map<String, String> fields = parseAllFields(text);
        String result = fields.get(fieldName);
        if (result != null && !result.isEmpty()) {
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
