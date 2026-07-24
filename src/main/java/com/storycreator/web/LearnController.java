package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.learn.LearnAudioService;
import com.storycreator.learn.MultiplicationFormula;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
        return "learn";
    }

    @GetMapping("/learn/multiplication")
    public String multiplication() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return "learn-multiplication";
    }

    @GetMapping("/learn/multiplication/settings")
    public String multiplicationSettings(Model model) {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        model.addAttribute("ttsConfigs", ttsProviderRegistry.getActiveTtsConfigs());
        model.addAttribute("defaultTtsConfigId", providerRouter.getGlobalDefaultTtsConfigId());
        model.addAttribute("formulas", MultiplicationFormula.FORMULAS);
        model.addAttribute("prefixes", MultiplicationFormula.PREFIXES);
        model.addAttribute("totalAudioCount", MultiplicationFormula.TOTAL_AUDIO_COUNT);
        return "learn-multiplication-settings";
    }
}
