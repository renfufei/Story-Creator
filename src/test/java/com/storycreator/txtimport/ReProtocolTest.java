package com.storycreator.txtimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逆向工程 SSE 协议：标记边界必须安全 —— 载荷里的任意内容（含 {@code ]]}、换行、
 * 中文、emoji）都不能破坏标记解析，否则 {@code TxtImportApiController} 的分流会错位。
 */
class ReProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Object decode(String token, String prefix) throws Exception {
        String payload = ReProtocol.unwrap(token, prefix);
        return MAPPER.readValue(Base64.getDecoder().decode(payload), Object.class);
    }

    @Test
    void itemPayloadWithHostileContentStaysParseable() throws Exception {
        // 正文里故意塞入协议结束标记、换行、中文、emoji
        String hostile = "===大纲===\n主角说 ]] 然后就 [[RE_PHASE:WORLD]] 了\n：中文冒号与 emoji 🙂";

        String token = ReProtocol.item(new ReProtocol.ReItem(
                "CHAPTER_OUTLINE", 3, 6, "第3章", "叁·密诏", hostile, "沈砚、周穆", false));

        // 标记内部绝不能出现 ']'，否则前缀匹配和结尾剥离都会错位
        String payload = ReProtocol.unwrap(token, "[[RE_ITEM:");
        assertThat(payload).doesNotContain("]").doesNotContain("[");
        assertThat(token).startsWith("[[RE_ITEM:").endsWith("]]");

        Object decoded = decode(token, "[[RE_ITEM:");
        assertThat(decoded).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) decoded;
        assertThat(map.get("phase")).isEqualTo("CHAPTER_OUTLINE");
        assertThat(map.get("index")).isEqualTo(3);
        assertThat(map.get("total")).isEqualTo(6);
        assertThat(map.get("body")).isEqualTo(hostile);
        assertThat(map.get("characters")).isEqualTo("沈砚、周穆");
        assertThat(map.get("skipped")).isEqualTo(false);
    }

    @Test
    void planPayloadRoundTrips() throws Exception {
        ReProtocol.RePlan plan = new ReProtocol.RePlan(
                7L, 42L, "INTERRUPTED", 30, 120, 4,
                List.of(
                        new ReProtocol.PhasePlan("CHAPTER_OUTLINE", "章节大纲与角色", 10, "PARTIAL", 120, 37, true),
                        new ReProtocol.PhasePlan("STORY_ARC", "故事弧线", 20, "PENDING", 4, 0, true),
                        new ReProtocol.PhasePlan("WORLD", "世界观汇总", 30, "SKIPPED", 1, 0, false)),
                129, 37, true, false);

        Object decoded = decode(ReProtocol.plan(plan), "[[RE_PLAN:");
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Object>) decoded;
        assertThat(map.get("jobId")).isEqualTo(7);
        assertThat(map.get("projectId")).isEqualTo(42);
        assertThat(map.get("jobStatus")).isEqualTo("INTERRUPTED");
        assertThat(map.get("completedUnits")).isEqualTo(37);
        assertThat(map.get("resumable")).isEqualTo(true);
        assertThat(map.get("completed")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        var phases = (java.util.List<java.util.Map<String, Object>>) map.get("phases");
        assertThat(phases).hasSize(3);
        assertThat(phases.get(0).get("phase")).isEqualTo("CHAPTER_OUTLINE");
        assertThat(phases.get(0).get("status")).isEqualTo("PARTIAL");
        assertThat(phases.get(2).get("runnable")).isEqualTo(false);
    }

    @Test
    void simpleMarkersCarryNoPayload() {
        assertThat(ReProtocol.phase(RePhase.STORY_ARC)).isEqualTo("[[RE_PHASE:STORY_ARC]]");
        assertThat(ReProtocol.phaseSkipped(RePhase.WORLD)).isEqualTo("[[RE_PHASE_SKIP:WORLD]]");
        assertThat(ReProtocol.phaseDone(RePhase.CHAPTER_OUTLINE, 6, 6))
                .isEqualTo("[[RE_PHASE_DONE:CHAPTER_OUTLINE|6|6]]");
        assertThat(ReProtocol.progress(3, 6)).isEqualTo("[[RE_PROGRESS:3/6]]");
    }

    @Test
    void unwrapToleratesMissingSuffix() {
        assertThat(ReProtocol.unwrap("[[RE_PHASE:WORLD]]", "[[RE_PHASE:"))
                .isEqualTo("WORLD");
        assertThat(ReProtocol.unwrap("[[RE_PHASE:WORLD", "[[RE_PHASE:"))
                .isEqualTo("WORLD");
        assertThat(ReProtocol.unwrap("[[RE_PHASE:", "[[RE_PHASE:"))
                .isEmpty();
    }

    @Test
    void base64PayloadIsUtf8ForChineseText() {
        String encoded = ReProtocol.encodeText("章节大纲");
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo("章节大纲");
    }
}
