package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.learn.CetWordBank;
import com.storycreator.learn.LearnAudioService;
import com.storycreator.learn.MultiplicationFormula;
import com.storycreator.learn.WordMatchBank;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class LearnController {

    private final LearnAudioService learnAudioService;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiProviderRouter providerRouter;
    private final WordMatchBank wordMatchBank;
    private final CetWordBank cetWordBank;

    public LearnController(LearnAudioService learnAudioService, TtsProviderRegistry ttsProviderRegistry,
                           AiProviderRouter providerRouter, WordMatchBank wordMatchBank,
                           CetWordBank cetWordBank) {
        this.learnAudioService = learnAudioService;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.providerRouter = providerRouter;
        this.wordMatchBank = wordMatchBank;
        this.cetWordBank = cetWordBank;
    }

    @GetMapping("/learn")
    public String learnIndex() {
        return "forward:/pages/learn.html";
    }

    @GetMapping("/learn/multiplication")
    public String multiplication() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return "forward:/pages/learn-multiplication.html";
    }

    // ==================== 英语单词匹配 ====================

    @GetMapping("/learn/word-match")
    public String wordMatch() {
        return "forward:/pages/learn-word-match.html";
    }

    /**
     * 单词匹配引导数据：全部册次 + 每册按主题切好的关卡（含全部单词对）。
     *
     * <p>一次性下发（人教版 24 册，gzip 约 116KB），换来切换册次 / 关卡零请求、离线可用；
     * 进度由前端 localStorage 维护，无需服务端状态。
     *
     * <p>{@code extraBooks} 是<b>大学</b>那两册的元信息（id / 名称 / 关数 / 词数）：它属于独立词源，
     * 全部关卡另有约 225KB（gzip），塞进这份首屏数据会把同步 XHR 的等待时间拉长两倍，所以只带元信息，
     * 关卡等用户真的选到大学时再走 {@link #wordMatchCet()}。
     */
    @GetMapping("/learn/word-match/data")
    @ResponseBody
    public Map<String, Object> wordMatchData() {
        Map<String, Object> data = new LinkedHashMap<>(wordMatchBank.bootstrapData());
        data.put("extraBooks", cetWordBank.bookIndex());
        return data;
    }

    /**
     * 大学词库（独立词源）：2 册（四级 7508 条 / 六级 5651 条）+ 切好的关卡。
     * 结构与 {@link #wordMatchData()} 完全一致，前端合并进同一份数据后无差别使用。
     */
    @GetMapping("/learn/word-match/cet")
    @ResponseBody
    public Map<String, Object> wordMatchCet() {
        return cetWordBank.bootstrapData();
    }

    @GetMapping("/learn/multiplication/settings")
    public String multiplicationSettings() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return "forward:/pages/learn-multiplication-settings.html";
    }

    /**
     * 乘法口诀音频管理页引导数据：TTS 配置 + 默认配置 + 口诀/前缀（供算式映射）+ 音频总数。
     *
     * <p>{@code extraParams} 原样回传 JSON 字符串（与原 Thymeleaf 序列化一致），由前端自行解析
     * 出 {@code voices} / {@code format}。
     */
    @GetMapping("/learn/multiplication/settings/data")
    @ResponseBody
    public Map<String, Object> multiplicationSettingsData() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);

        List<Map<String, Object>> configs = ttsProviderRegistry.getActiveTtsConfigs().stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("displayName", c.getDisplayName());
                    m.put("modelId", c.getModelId());
                    m.put("extraParams", c.getExtraParams());
                    return m;
                })
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("ttsConfigs", configs);
        result.put("defaultTtsConfigId", providerRouter.getGlobalDefaultTtsConfigId());
        result.put("formulas", MultiplicationFormula.FORMULAS);
        result.put("prefixes", MultiplicationFormula.PREFIXES);
        result.put("totalAudioCount", MultiplicationFormula.TOTAL_AUDIO_COUNT);
        return result;
    }
}
