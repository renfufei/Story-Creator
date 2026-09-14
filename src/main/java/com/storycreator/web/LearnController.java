package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.learn.LearnAudioService;
import com.storycreator.learn.MultiplicationFormula;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class LearnController {

    private final LearnAudioService learnAudioService;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiProviderRouter providerRouter;

    public LearnController(LearnAudioService learnAudioService, TtsProviderRegistry ttsProviderRegistry, AiProviderRouter providerRouter) {
        this.learnAudioService = learnAudioService;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.providerRouter = providerRouter;
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
