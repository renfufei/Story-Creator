package com.storycreator.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link TtsExtraParams} 的单元测试 —— 正式导出与设置页「测试连接」共用这条参数链，
 * 两边语义必须一致（systemPrompt 前缀、noVoice 不发 voice）。
 */
class TtsExtraParamsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* ---------- applySystemPrompt ---------- */

    @Test
    void applySystemPrompt_prependsWhenPresent() {
        assertEquals("(风格)正文", TtsExtraParams.applySystemPrompt("正文", "{\"systemPrompt\":\"(风格)\"}"));
    }

    @Test
    void applySystemPrompt_keepsInputWhenAbsent() {
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", null));
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", ""));
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", "{}"));
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", "{\"noVoice\":true}"));
    }

    @Test
    void applySystemPrompt_ignoresBlankPromptAndBrokenJson() {
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", "{\"systemPrompt\":\"   \"}"));
        assertEquals("正文", TtsExtraParams.applySystemPrompt("正文", "not-json"));
    }

    /* ---------- resolveVoice ---------- */

    @Test
    void resolveVoice_returnsNullWhenNoVoice() {
        assertNull(TtsExtraParams.resolveVoice("serena", "{\"noVoice\":true}"));
        assertNull(TtsExtraParams.resolveVoice(null, "{\"noVoice\":true}"));
    }

    @Test
    void resolveVoice_keepsVoiceWhenNoVoiceFalseOrAbsent() {
        assertEquals("serena", TtsExtraParams.resolveVoice("serena", "{\"noVoice\":false}"));
        assertEquals("serena", TtsExtraParams.resolveVoice("serena", "{}"));
        assertEquals("serena", TtsExtraParams.resolveVoice("serena", null));
    }

    @Test
    void resolveVoice_fallsBackToDefault() {
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.resolveVoice(null, "{}"));
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.resolveVoice(null, "broken"));
    }

    /* ---------- extractVoice ---------- */

    @Test
    void extractVoice_readsConfiguredVoice() {
        assertEquals("serena", TtsExtraParams.extractVoice("{\"voice\":\"serena\"}"));
    }

    @Test
    void extractVoice_fallsBackToDefault() {
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.extractVoice(null));
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.extractVoice("{}"));
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.extractVoice("{\"voice\":\"  \"}"));
        assertEquals(TtsExtraParams.DEFAULT_VOICE, TtsExtraParams.extractVoice("broken"));
    }

    /* ---------- mergeOverrides ---------- */

    @Test
    void mergeOverrides_returnsOriginalWhenNothingToMerge() {
        String src = "{\"voice\":\"serena\"}";
        assertSame(src, TtsExtraParams.mergeOverrides(src, Map.of()));
        assertSame(src, TtsExtraParams.mergeOverrides(src, Map.of("input", "x")));
        assertSame(src, TtsExtraParams.mergeOverrides(src, null));
    }

    @Test
    void mergeOverrides_preservesExistingKeysAndAddsOverrides() throws Exception {
        String merged = TtsExtraParams.mergeOverrides(
                "{\"voice\":\"serena\",\"voices\":\"serena,vivian\"}",
                Map.of("systemPrompt", "(风格)", "noVoice", true));
        var node = MAPPER.readTree(merged);
        assertEquals("serena", node.path("voice").asText());
        assertEquals("serena,vivian", node.path("voices").asText());
        assertEquals("(风格)", node.path("systemPrompt").asText());
        assertEquals(true, node.path("noVoice").asBoolean());
    }

    @Test
    void mergeOverrides_treatsStringTrueAsBoolean() throws Exception {
        var node = MAPPER.readTree(TtsExtraParams.mergeOverrides(null, Map.of("noVoice", "true")));
        assertEquals(true, node.path("noVoice").asBoolean());
    }

    @Test
    void mergeOverrides_startsFreshOnBrokenInput() throws Exception {
        var node = MAPPER.readTree(TtsExtraParams.mergeOverrides("not-json", Map.of("systemPrompt", "(x)")));
        assertEquals("(x)", node.path("systemPrompt").asText());
    }
}
