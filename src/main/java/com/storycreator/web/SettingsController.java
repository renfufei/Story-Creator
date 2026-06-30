package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.port.image.ImageRequest;
import com.storycreator.core.port.image.ImageResult;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.tts.template.TtsReplacementBuiltinLoader;
import com.storycreator.tts.template.TtsReplacementTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final AiModelConfigRepository configRepository;
    private final AiProviderRouter providerRouter;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final ImageProviderRegistry imageProviderRegistry;
    private final GlobalSettingService globalSettingService;
    private final TtsReplacementBuiltinLoader builtinLoader;
    private final TtsReplacementTemplateService ttsReplacementTemplateService;
    private final Environment environment;

    public SettingsController(AiModelConfigRepository configRepository,
                             AiProviderRouter providerRouter,
                             TtsProviderRegistry ttsProviderRegistry,
                             ImageProviderRegistry imageProviderRegistry,
                             GlobalSettingService globalSettingService,
                             TtsReplacementBuiltinLoader builtinLoader,
                             TtsReplacementTemplateService ttsReplacementTemplateService,
                             Environment environment) {
        this.configRepository = configRepository;
        this.providerRouter = providerRouter;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.imageProviderRegistry = imageProviderRegistry;
        this.globalSettingService = globalSettingService;
        this.builtinLoader = builtinLoader;
        this.ttsReplacementTemplateService = ttsReplacementTemplateService;
        this.environment = environment;
    }

    @GetMapping
    public String settings(Model model) {
        List<AiModelConfigEntity> allConfigs = configRepository.findAll();
        List<AiModelConfigEntity> textConfigs = allConfigs.stream()
                .filter(c -> c.getModelType() == ModelType.TEXT)
                .toList();
        List<AiModelConfigEntity> ttsConfigs = allConfigs.stream()
                .filter(c -> c.getModelType() == ModelType.TTS)
                .toList();

        List<AiModelConfigEntity> imageConfigs = allConfigs.stream()
                .filter(c -> c.getModelType() == ModelType.IMAGE)
                .toList();

        model.addAttribute("textConfigs", textConfigs);
        model.addAttribute("ttsConfigs", ttsConfigs);
        model.addAttribute("imageConfigs", imageConfigs);
        model.addAttribute("globalDefaultId", providerRouter.getGlobalDefaultConfigId());
        model.addAttribute("globalDefaultTtsId", providerRouter.getGlobalDefaultTtsConfigId());
        model.addAttribute("globalDefaultImageId", imageProviderRegistry.getGlobalDefaultImageConfigId());
        model.addAttribute("aiTimeoutSeconds", globalSettingService.getAiTimeoutSeconds());
        model.addAttribute("ttsDebugMode", globalSettingService.isTtsDebugMode());
        return "settings";
    }

    @PostMapping("/global-default")
    public String setGlobalDefault(@RequestParam Long modelConfigId) {
        providerRouter.setGlobalDefaultConfigId(modelConfigId);
        return "redirect:/settings";
    }

    @PostMapping("/global-default-tts")
    public String setGlobalDefaultTts(@RequestParam Long modelConfigId) {
        providerRouter.setGlobalDefaultTtsConfigId(modelConfigId);
        return "redirect:/settings";
    }

    @PostMapping("/global-default-image")
    public String setGlobalDefaultImage(@RequestParam Long modelConfigId) {
        imageProviderRegistry.setGlobalDefaultImageConfigId(modelConfigId);
        return "redirect:/settings";
    }

    @PostMapping("/ai-timeout")
    public String setAiTimeout(@RequestParam int timeoutSeconds) {
        if (timeoutSeconds < 30) timeoutSeconds = 30;
        if (timeoutSeconds > 3600) timeoutSeconds = 3600;
        globalSettingService.setAiTimeoutSeconds(timeoutSeconds);
        return "redirect:/settings";
    }

    @PostMapping("/tts-debug-mode")
    public String setTtsDebugMode(@RequestParam(defaultValue = "false") boolean enabled) {
        globalSettingService.setTtsDebugMode(enabled);
        return "redirect:/settings";
    }

    @PostMapping("/ai-models/{id}")
    public String updateModelConfig(@PathVariable Long id,
                                   @RequestParam(required = false) String displayName,
                                   @RequestParam(required = false) String apiKey,
                                   @RequestParam(required = false) String baseUrl,
                                   @RequestParam(required = false) String extraParams,
                                   @RequestParam(defaultValue = "false") boolean active,
                                   @RequestParam(defaultValue = "false") boolean clearApiKey) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        if (displayName != null && !displayName.isBlank()) {
            config.setDisplayName(displayName);
        }
        if (clearApiKey) {
            config.setApiKey(null);
        } else if (apiKey != null && !apiKey.isBlank()) {
            config.setApiKey(apiKey);
        }
        config.setBaseUrl(baseUrl != null ? baseUrl.trim() : null);
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
                                @RequestParam(required = false) String extraParams,
                                @RequestParam(defaultValue = "TEXT") String modelType) {
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider(provider);
        config.setModelId(modelId);
        config.setDisplayName(displayName);
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setExtraParams(extraParams != null && !extraParams.isBlank() ? extraParams.trim() : null);
        config.setModelType(ModelType.valueOf(modelType));
        config.setActive(true);
        config = configRepository.save(config);

        if (config.getModelType() == ModelType.TTS) {
            var builtinTemplates = builtinLoader.getAll();
            for (int i = 0; i < builtinTemplates.size(); i++) {
                ttsReplacementTemplateService.bindTemplateToConfig(
                        config.getId(), "builtin:" + builtinTemplates.get(i).id(), i);
            }
        }

        return "redirect:/settings";
    }

    @PostMapping("/ai-models/{id}/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        try {
            AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModelByConfigId(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法找到对应的Provider"));
            }

            String systemPrompt = "You are a helpful assistant.";
            String userPrompt = "请回复'连接成功'四个字。";
            int maxTokens = 20;
            double temperature = 0.1;

            if (body != null) {
                if (body.containsKey("systemPrompt")) systemPrompt = (String) body.get("systemPrompt");
                if (body.containsKey("userPrompt")) userPrompt = (String) body.get("userPrompt");
                if (body.containsKey("maxTokens")) maxTokens = ((Number) body.get("maxTokens")).intValue();
                if (body.containsKey("temperature")) temperature = ((Number) body.get("temperature")).doubleValue();
            }

            AiRequest request = AiRequest.builder()
                    .model(config.getModelId())
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .maxTokens(maxTokens)
                    .temperature(temperature)
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

    @PostMapping("/ai-models/{id}/test-tts")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testTtsConnection(@PathVariable Long id,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        if (config.getModelType() != ModelType.TTS) {
            return ResponseEntity.ok(Map.of("success", false, "message", "该配置不是TTS类型"));
        }

        try {
            TtsProviderRegistry.ResolvedTtsConfig resolved = ttsProviderRegistry.resolve(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法解析TTS配置"));
            }

            String voice = extractVoiceFromExtraParams(config.getExtraParams());
            String input = "测试。";
            double speed = 1.0;
            String format = "mp3";

            if (body != null) {
                if (body.containsKey("input")) input = (String) body.get("input");
                if (body.containsKey("voice")) voice = (String) body.get("voice");
                if (body.containsKey("speed")) speed = ((Number) body.get("speed")).doubleValue();
                if (body.containsKey("format")) format = (String) body.get("format");
            }

            TtsRequest request = TtsRequest.builder()
                    .model(config.getModelId())
                    .input(input)
                    .voice(voice)
                    .responseFormat(format)
                    .speed(speed)
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .build();

            byte[] audio = resolved.provider().generateAudio(request);
            String audioBase64 = java.util.Base64.getEncoder().encodeToString(audio);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "TTS连接成功，生成音频 " + audio.length + " 字节",
                    "audio", audioBase64));
        } catch (Exception e) {
            log.error("TTS connection test failed for config {}", id, e);
            String msg = e.getMessage();
            if (msg != null && msg.length() > 500) msg = msg.substring(0, 500);
            return ResponseEntity.ok(Map.of("success", false, "message", "连接失败: " + msg));
        }
    }

    @PostMapping("/ai-models/{id}/test-image")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testImageConnection(@PathVariable Long id,
                                                                    @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        if (config.getModelType() != ModelType.IMAGE) {
            return ResponseEntity.ok(Map.of("success", false, "message", "该配置不是IMAGE类型"));
        }

        try {
            var resolved = imageProviderRegistry.resolve(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法解析图像配置"));
            }

            String prompt = "A red circle on a white background";
            int width = 1024;
            int height = 1024;

            if (body != null) {
                if (body.containsKey("prompt")) prompt = (String) body.get("prompt");
                if (body.containsKey("width")) width = ((Number) body.get("width")).intValue();
                if (body.containsKey("height")) height = ((Number) body.get("height")).intValue();
            }

            ImageRequest request = ImageRequest.builder()
                    .model(resolved.modelId())
                    .prompt(prompt)
                    .width(width)
                    .height(height)
                    .baseUrl(resolved.baseUrl())
                    .apiKey(resolved.apiKey())
                    .extraParams(resolved.extraParams())
                    .build();

            ImageResult result = resolved.provider().generateImage(request);
            String imageBase64 = java.util.Base64.getEncoder().encodeToString(result.imageBytes());
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "生成成功, " + result.imageBytes().length + " bytes",
                    "imageBase64", imageBase64));
        } catch (Exception e) {
            log.error("Image connection test failed for config {}", id, e);
            String msg = e.getMessage();
            if (msg != null && msg.length() > 500) msg = msg.substring(0, 500);
            return ResponseEntity.ok(Map.of("success", false, "message", "连接失败: " + msg));
        }
    }

    @PostMapping("/ai-models/{id}/probe-voices")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> probeVoices(@PathVariable Long id) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));

        if (config.getModelType() != ModelType.TTS) {
            return ResponseEntity.ok(Map.of("success", false, "message", "该配置不是TTS类型"));
        }

        try {
            TtsProviderRegistry.ResolvedTtsConfig resolved = ttsProviderRegistry.resolve(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法解析TTS配置"));
            }

            // Send request with invalid voice to trigger error listing available voices
            TtsRequest request = TtsRequest.builder()
                    .model(config.getModelId())
                    .input("test")
                    .voice("__probe_invalid_voice__")
                    .responseFormat("mp3")
                    .speed(1.0)
                    .baseUrl(config.getBaseUrl())
                    .apiKey(config.getApiKey())
                    .build();

            try {
                resolved.provider().generateAudio(request);
                // If it succeeds (unlikely), the provider accepts any voice name
                return ResponseEntity.ok(Map.of("success", false, "message", "该TTS服务接受任意音色名称，无法探测具体列表"));
            } catch (Exception probeErr) {
                String errMsg = probeErr.getMessage();
                // Try to parse available voices from error message
                // Common format: "Available: ['voice1', 'voice2']" or "Available speakers: [...]"
                String voices = parseVoicesFromError(errMsg);
                if (voices != null) {
                    return ResponseEntity.ok(Map.of("success", true,
                            "message", "可用音色: " + voices,
                            "voices", voices));
                } else {
                    return ResponseEntity.ok(Map.of("success", false,
                            "message", "无法解析音色列表。错误信息: " + (errMsg != null && errMsg.length() > 500 ? errMsg.substring(0, 500) : errMsg)));
                }
            }
        } catch (Exception e) {
            log.error("Voice probe failed for config {}", id, e);
            String msg = e.getMessage();
            if (msg != null && msg.length() > 300) msg = msg.substring(0, 300);
            return ResponseEntity.ok(Map.of("success", false, "message", "探测失败: " + msg));
        }
    }


    @PostMapping("/ai-models/add-mock")
    public String addMockModel() {
        String port = environment.getProperty("local.server.port", "8080");
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider("openai");
        config.setModelId("mock-model");
        config.setDisplayName("Mock模型（内置）");
        config.setApiKey("mock-key");
        config.setBaseUrl("http://localhost:" + port + "/mock");
        config.setModelType(ModelType.TEXT);
        config.setActive(true);
        configRepository.save(config);
        return "redirect:/settings";
    }

    private String parseVoicesFromError(String errorMsg) {
        if (errorMsg == null) return null;
        // Match patterns like: Available: ['voice1', 'voice2', ...] or Available speakers: [...]
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)available[^:]*:\\s*\\[([^\\]]+)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(errorMsg);
        if (matcher.find()) {
            String raw = matcher.group(1);
            // Clean up: remove quotes, trim
            return raw.replaceAll("[\"']", "").trim();
        }
        return null;
    }

    private String extractVoiceFromExtraParams(String extraParams) {
        if (extraParams != null && !extraParams.isBlank()) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extraParams);
                if (node.has("voice")) {
                    return node.get("voice").asText();
                }
            } catch (Exception ignored) {}
        }
        return "alloy";
    }
}
