package com.storycreator.txtimport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 逆向工程 SSE 通道内的控制协议。
 *
 * <p>所有结构化载荷（JSON / 自由文本）统一做 Base64 编码放在标记内，
 * 避免内容里出现 {@code ]]} 或换行破坏标记边界；标记本身只含 ASCII，
 * 便于 {@code TxtImportApiController} 用前缀匹配分流成不同 SSE 事件。
 *
 * <p>标记一览：
 * <ul>
 *   <li>{@code [[RE_PLAN:<b64json>]]}       任务计划（各阶段完成度），进入流程时下发一次</li>
 *   <li>{@code [[RE_PHASE:<NAME>]]}         某阶段开始</li>
 *   <li>{@code [[RE_PHASE_DONE:NAME|d|t]]}  某阶段完成（d=已完成单元，t=总单元）</li>
 *   <li>{@code [[RE_PHASE_SKIP:<NAME>]]}    某阶段跳过（已完成或按选项关闭）</li>
 *   <li>{@code [[RE_NOTE:<b64json>]]}       阶段提示文字</li>
 *   <li>{@code [[RE_ITEM:<b64json>]]}       单元产出（用于实时展示）</li>
 *   <li>{@code [[RE_PROGRESS:i/total]]}     阶段内进度</li>
 * </ul>
 */
public final class ReProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReProtocol() {}

    /** 单元产出：章节大纲 / 故事弧线 / 汇总分块。 */
    public record ReItem(String phase, int index, int total, String key, String title,
                         String body, String characters, boolean skipped) {}

    /** 阶段计划项。 */
    public record PhasePlan(String phase, String label, int sortOrder, String status,
                            int totalUnits, int completedUnits, boolean runnable) {}

    /** 任务整体计划。 */
    public record RePlan(long jobId, Long projectId, String jobStatus, int chaptersPerVolume,
                         int totalChapters, int totalVolumes,
                         java.util.List<PhasePlan> phases,
                         int totalUnits, int completedUnits,
                         boolean resumable, boolean completed) {}

    /** 阶段提示。 */
    public record ReNote(String phase, String text) {}

    public static String plan(RePlan plan) {
        return "[[RE_PLAN:" + b64(plan) + "]]";
    }

    public static String phase(RePhase phase) {
        return "[[RE_PHASE:" + phase.name() + "]]";
    }

    public static String phaseDone(RePhase phase, int done, int total) {
        return "[[RE_PHASE_DONE:" + phase.name() + "|" + done + "|" + total + "]]";
    }

    public static String phaseSkipped(RePhase phase) {
        return "[[RE_PHASE_SKIP:" + phase.name() + "]]";
    }

    public static String note(RePhase phase, String text) {
        return "[[RE_NOTE:" + b64(new ReNote(phase.name(), text)) + "]]";
    }

    public static String item(ReItem item) {
        return "[[RE_ITEM:" + b64(item) + "]]";
    }

    public static String progress(int current, int total) {
        return "[[RE_PROGRESS:" + current + "/" + total + "]]";
    }

    /**
     * 去掉 {@code [[TAG:} 前缀与结尾的 {@code ]]}}，得到标记内的原始载荷。
     * 容错：结尾的 {@code ]]}} 缺失时不报错（避免半截 token 直接 500）。
     */
    public static String unwrap(String token, String prefix) {
        if (token == null || !token.startsWith(prefix)) return "";
        int start = prefix.length();
        int end = token.endsWith("]]") ? token.length() - 2 : token.length();
        return start <= end ? token.substring(start, end) : "";
    }

    private static String b64(Object value) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("逆向工程协议序列化失败: " + e.getMessage(), e);
        }
    }

    /** 供前端解码参考：UTF-8 字节的 Base64。 */
    public static String encodeText(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
