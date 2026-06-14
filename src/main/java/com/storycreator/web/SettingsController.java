package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final AiModelConfigRepository configRepository;
    private final AiProviderRouter providerRouter;
    private final GlobalSettingService globalSettingService;

    public SettingsController(AiModelConfigRepository configRepository,
                             AiProviderRouter providerRouter,
                             GlobalSettingService globalSettingService) {
        this.configRepository = configRepository;
        this.providerRouter = providerRouter;
        this.globalSettingService = globalSettingService;
    }

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("configs", configRepository.findAll());
        model.addAttribute("globalDefaultId", providerRouter.getGlobalDefaultConfigId());
        model.addAttribute("aiTimeoutSeconds", globalSettingService.getAiTimeoutSeconds());
        return "settings";
    }

    @PostMapping("/global-default")
    public String setGlobalDefault(@RequestParam Long modelConfigId) {
        providerRouter.setGlobalDefaultConfigId(modelConfigId);
        return "redirect:/settings";
    }

    @PostMapping("/ai-timeout")
    public String setAiTimeout(@RequestParam int timeoutSeconds) {
        if (timeoutSeconds < 30) timeoutSeconds = 30;
        if (timeoutSeconds > 3600) timeoutSeconds = 3600;
        globalSettingService.setAiTimeoutSeconds(timeoutSeconds);
        return "redirect:/settings";
    }

    @PostMapping("/ai-models/{id}")
    public String updateModelConfig(@PathVariable Long id,
                                   @RequestParam(required = false) String apiKey,
                                   @RequestParam(required = false) String baseUrl,
                                   @RequestParam(required = false) String extraParams,
                                   @RequestParam(defaultValue = "false") boolean active) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        if (apiKey != null && !apiKey.isBlank()) config.setApiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) config.setBaseUrl(baseUrl);
        config.setExtraParams(extraParams != null && !extraParams.isBlank() ? extraParams.trim() : null);
        config.setActive(active);
        configRepository.save(config);

        return "redirect:/settings";
    }

    @PostMapping("/ai-models/{id}/delete")
    public String deleteModelConfig(@PathVariable Long id) {
        configRepository.deleteById(id);
        return "redirect:/settings";
    }

    @PostMapping("/ai-models")
    public String addModelConfig(@RequestParam String provider,
                                @RequestParam String modelId,
                                @RequestParam String displayName,
                                @RequestParam(required = false) String apiKey,
                                @RequestParam(required = false) String baseUrl,
                                @RequestParam(required = false) String extraParams) {
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider(provider);
        config.setModelId(modelId);
        config.setDisplayName(displayName);
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setExtraParams(extraParams != null && !extraParams.isBlank() ? extraParams.trim() : null);
        config.setActive(true);
        configRepository.save(config);

        return "redirect:/settings";
    }

    @PostMapping("/ai-models/{id}/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        try {
            // Use the specific provider for this config
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModelByConfigId(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法找到对应的Provider"));
            }

            AiRequest request = AiRequest.builder()
                    .model(config.getModelId())
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .systemPrompt("You are a helpful assistant.")
                    .userPrompt("请回复'连接成功'四个字。")
                    .maxTokens(20)
                    .temperature(0.1)
                    .build();

            String result = resolved.provider().generateText(request);
            return ResponseEntity.ok(Map.of("success", true, "message", "连接成功: " + result.trim()));
        } catch (Exception e) {
            log.error("Connection test failed for config {}", id, e);
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) msg = msg.substring(0, 200);
            return ResponseEntity.ok(Map.of("success", false, "message", "连接失败: " + msg));
        }
    }
}
