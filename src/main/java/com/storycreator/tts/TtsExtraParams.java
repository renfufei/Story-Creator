package com.storycreator.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * TTS {@code extraParams} 的解析工具。
 *
 * <p>正式导出（{@link TtsService}）与设置页「测试连接」（{@code SettingsApiController#testTtsConnection}）
 * 共用同一条处理链 —— 否则测试弹窗里改的参数与真正导出时的行为不一致。
 *
 * <p>支持的 TTS 相关键：{@code voice}（默认音色）、{@code voices}（候选音色列表，逗号分隔）、
 * {@code systemPrompt}（拼在正文前的风格前缀，VoxCPM 风格用）、{@code noVoice=true}（不发送 voice 字段）。
 */
public final class TtsExtraParams {

    /** 未指定音色时回落的默认值。 */
    public static final String DEFAULT_VOICE = "alloy";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TtsExtraParams() {
    }

    /** {@code systemPrompt} 非空时作为前缀拼到正文前，否则原样返回。 */
    public static String applySystemPrompt(String input, String extraParams) {
        String prompt = text(extraParams, "systemPrompt");
        return (prompt == null || prompt.isBlank()) ? input : prompt + input;
    }

    /** {@code noVoice=true} 时返回 {@code null}（不发送 voice 字段），否则回落给定音色或默认音色。 */
    public static String resolveVoice(String voice, String extraParams) {
        JsonNode node = parse(extraParams);
        if (node != null && node.path("noVoice").asBoolean(false)) {
            return null;
        }
        return voice != null ? voice : DEFAULT_VOICE;
    }

    /** 从 {@code extraParams} 里取 {@code voice}，取不到回落默认音色。 */
    public static String extractVoice(String extraParams) {
        String voice = text(extraParams, "voice");
        return (voice == null || voice.isBlank()) ? DEFAULT_VOICE : voice;
    }

    /**
     * 把调用方传进来的覆盖项（{@code systemPrompt} / {@code noVoice}）合并进原有 extraParams，
     * 返回新的 JSON 字符串；没有可合并的键时原样返回 {@code extraParams}。
     */
    public static String mergeOverrides(String extraParams, Map<String, Object> overrides) {
        if (overrides == null
                || (!overrides.containsKey("systemPrompt") && !overrides.containsKey("noVoice"))) {
            return extraParams;
        }
        ObjectNode node = object(extraParams);
        if (overrides.containsKey("systemPrompt")) {
            node.put("systemPrompt", String.valueOf(overrides.get("systemPrompt")));
        }
        if (overrides.containsKey("noVoice")) {
            node.put("noVoice", asBoolean(overrides.get("noVoice")));
        }
        return node.toString();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static JsonNode parse(String extraParams) {
        if (extraParams == null || extraParams.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(extraParams);
        } catch (Exception e) {
            return null;
        }
    }

    private static ObjectNode object(String extraParams) {
        JsonNode node = parse(extraParams);
        return (node != null && node.isObject()) ? (ObjectNode) node : MAPPER.createObjectNode();
    }

    private static String text(String extraParams, String field) {
        JsonNode node = parse(extraParams);
        return node == null ? null : node.path(field).asText(null);
    }
}
