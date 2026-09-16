package com.storycreator.api;

import org.springframework.beans.factory.annotation.Autowired;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置页（AI 模型配置）的 JSON API。
 *
 * <p>前端已从 Thymeleaf 迁移为「静态 HTML + JS + Ajax」，本控制器取代原 {@code SettingsController}
 * 的表单提交（{@code redirect:/settings}）与 {@code @ResponseBody} 测试接口，对外统一提供 {@code /api/settings/**}。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    private static final Logger log = LoggerFactory.getLogger(SettingsApiController.class);

    private AiModelConfigRepository configRepository;
    private AiProviderRouter providerRouter;
    private TtsProviderRegistry ttsProviderRegistry;
    private ImageProviderRegistry imageProviderRegistry;
    private GlobalSettingService globalSettingService;
    private TtsReplacementBuiltinLoader builtinLoader;
    private TtsReplacementTemplateService ttsReplacementTemplateService;
    private Environment environment;

    @Autowired
    public void setConfigRepository(AiModelConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Autowired
    public void setProviderRouter(AiProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    @Autowired
    public void setTtsProviderRegistry(TtsProviderRegistry ttsProviderRegistry) {
        this.ttsProviderRegistry = ttsProviderRegistry;
    }

    @Autowired
    public void setImageProviderRegistry(ImageProviderRegistry imageProviderRegistry) {
        this.imageProviderRegistry = imageProviderRegistry;
    }

    @Autowired
    public void setGlobalSettingService(GlobalSettingService globalSettingService) {
        this.globalSettingService = globalSettingService;
    }

    @Autowired
    public void setBuiltinLoader(TtsReplacementBuiltinLoader builtinLoader) {
        this.builtinLoader = builtinLoader;
    }

    @Autowired
    public void setTtsReplacementTemplateService(TtsReplacementTemplateService ttsReplacementTemplateService) {
        this.ttsReplacementTemplateService = ttsReplacementTemplateService;
    }

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }


    /* ============================ 读取 ============================ */

    @GetMapping
    public Map<String, Object> getSettings() {
        List<AiModelConfigEntity> all = configRepository.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("textConfigs", all.stream().filter(c -> c.getModelType() == ModelType.TEXT).map(this::toView).toList());
        out.put("ttsConfigs", all.stream().filter(c -> c.getModelType() == ModelType.TTS).map(this::toView).toList());
        out.put("imageConfigs", all.stream().filter(c -> c.getModelType() == ModelType.IMAGE).map(this::toView).toList());
        out.put("globalDefaultId", providerRouter.getGlobalDefaultConfigId());
        out.put("globalDefaultTtsId", providerRouter.getGlobalDefaultTtsConfigId());
        out.put("globalDefaultImageId", imageProviderRegistry.getGlobalDefaultImageConfigId());
        out.put("aiTimeoutSeconds", globalSettingService.getAiTimeoutSeconds());
        out.put("ttsDebugMode", globalSettingService.isTtsDebugMode());
        out.put("defaultAuthor", globalSettingService.getDefaultAuthor());
        return out;
    }

    private Map<String, Object> toView(AiModelConfigEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("provider", c.getProvider());
        m.put("modelId", c.getModelId());
        m.put("displayName", c.getDisplayName());
        m.put("apiKeyMasked", maskApiKey(c.getApiKey()));
        m.put("baseUrl", c.getBaseUrl());
        m.put("extraParams", c.getExtraParams());
        m.put("preCallDelaySeconds", c.getPreCallDelaySeconds());
        m.put("modelType", c.getModelType() == null ? "TEXT" : c.getModelType().name());
        m.put("active", c.isActive());
        return m;
    }

    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) return null;
        if (key.length() > 6) return key.substring(0, 3) + "..." + key.substring(key.length() - 4);
        return "***";
    }

    /* ============================ 全局设置 ============================ */

    @PostMapping("/global-default")
    public Map<String, Object> setGlobalDefault(@RequestBody Map<String, Object> body) {
        providerRouter.setGlobalDefaultConfigId(toLong(body.get("modelConfigId")));
        return ok();
    }

    @PostMapping("/global-default-tts")
    public Map<String, Object> setGlobalDefaultTts(@RequestBody Map<String, Object> body) {
        providerRouter.setGlobalDefaultTtsConfigId(toLong(body.get("modelConfigId")));
        return ok();
    }

    @PostMapping("/global-default-image")
    public Map<String, Object> setGlobalDefaultImage(@RequestBody Map<String, Object> body) {
        imageProviderRegistry.setGlobalDefaultImageConfigId(toLong(body.get("modelConfigId")));
        return ok();
    }

    @PostMapping("/ai-timeout")
    public Map<String, Object> setAiTimeout(@RequestBody Map<String, Object> body) {
        int t = toInt(body.get("timeoutSeconds"));
        if (t < 30) t = 30;
        if (t > 3600) t = 3600;
        globalSettingService.setAiTimeoutSeconds(t);
        return ok();
    }

    @PostMapping("/tts-debug-mode")
    public Map<String, Object> setTtsDebugMode(@RequestBody Map<String, Object> body) {
        boolean enabled = body.get("enabled") != null && Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        globalSettingService.setTtsDebugMode(enabled);
        return ok();
    }

    @PostMapping("/default-author")
    public Map<String, Object> setDefaultAuthor(@RequestBody Map<String, Object> body) {
        String author = body.get("author") == null ? "" : String.valueOf(body.get("author")).trim();
        globalSettingService.setDefaultAuthor(author);
        return ok();
    }

    /* ============================ 模型增删改 ============================ */

    @PostMapping("/ai-models")
    public Map<String, Object> addModelConfig(@RequestBody Map<String, Object> body) {
        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setProvider(String.valueOf(body.get("provider")).trim());
        config.setModelId(String.valueOf(body.get("modelId")).trim());
        config.setDisplayName(String.valueOf(body.get("displayName")).trim());
        config.setApiKey(strOrNull(body.get("apiKey")));
        config.setBaseUrl(strOrNull(body.get("baseUrl")));
        String ep = strOrNull(body.get("extraParams"));
        config.setExtraParams(ep != null && !ep.isBlank() ? ep.trim() : null);
        config.setPreCallDelaySeconds(clampDelay(toInt(body.get("preCallDelaySeconds"))));
        config.setModelType(ModelType.valueOf(strOrNull(body.get("modelType")) == null ? "TEXT" : strOrNull(body.get("modelType")).toUpperCase(Locale.ROOT)));
        config.setActive(true);
        config = configRepository.save(config);

        if (config.getModelType() == ModelType.TTS) {
            var builtinTemplates = builtinLoader.getAll();
            for (int i = 0; i < builtinTemplates.size(); i++) {
                ttsReplacementTemplateService.bindTemplateToConfig(
                        config.getId(), "builtin:" + builtinTemplates.get(i).id(), i);
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("id", config.getId());
        return r;
    }

    @PutMapping("/ai-models/{id}")
    public Map<String, Object> updateModelConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found: " + id));

        if (body.containsKey("displayName")) {
            String dn = String.valueOf(body.get("displayName")).trim();
            if (!dn.isBlank()) config.setDisplayName(dn);
        }
        boolean clearApiKey = body.get("clearApiKey") != null && Boolean.parseBoolean(String.valueOf(body.get("clearApiKey")));
        if (clearApiKey) {
            config.setApiKey(null);
        } else if (body.containsKey("apiKey")) {
            String ak = String.valueOf(body.get("apiKey"));
            if (ak != null && !ak.isBlank()) config.setApiKey(ak.trim());
        }
        if (body.containsKey("baseUrl")) config.setBaseUrl(strOrNull(body.get("baseUrl")));
        if (body.containsKey("extraParams")) {
            String ep = strOrNull(body.get("extraParams"));
            config.setExtraParams(ep != null && !ep.isBlank() ? ep.trim() : null);
        }
        if (body.containsKey("preCallDelaySeconds")) config.setPreCallDelaySeconds(clampDelay(toInt(body.get("preCallDelaySeconds"))));
        if (body.containsKey("active")) config.setActive(Boolean.parseBoolean(String.valueOf(body.get("active"))));
        configRepository.save(config);
        return ok();
    }

    @DeleteMapping("/ai-models/{id}")
    public Map<String, Object> deleteModelConfig(@PathVariable Long id) {
        configRepository.deleteById(id);
        return ok();
    }

    @PostMapping("/ai-models/add-mock")
    public Map<String, Object> addMockModel() {
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
        return ok();
    }

    /* ============================ 连接测试 ============================ */

    @PostMapping("/ai-models/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found: " + id));

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
    public ResponseEntity<Map<String, Object>> testTtsConnection(@PathVariable Long id,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found: " + id));

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
            String audioBase64 = Base64.getEncoder().encodeToString(audio);
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
    public ResponseEntity<Map<String, Object>> testImageConnection(@PathVariable Long id,
                                                                    @RequestBody(required = false) Map<String, Object> body) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found: " + id));

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
            String imageBase64 = Base64.getEncoder().encodeToString(result.imageBytes());
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
    public ResponseEntity<Map<String, Object>> probeVoices(@PathVariable Long id) {
        AiModelConfigEntity config = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found: " + id));

        if (config.getModelType() != ModelType.TTS) {
            return ResponseEntity.ok(Map.of("success", false, "message", "该配置不是TTS类型"));
        }

        try {
            TtsProviderRegistry.ResolvedTtsConfig resolved = ttsProviderRegistry.resolve(id);
            if (resolved == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法解析TTS配置"));
            }

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
                return ResponseEntity.ok(Map.of("success", false, "message", "该TTS服务接受任意音色名称，无法探测具体列表"));
            } catch (Exception probeErr) {
                String errMsg = probeErr.getMessage();
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

    /* ============================ 工具方法 ============================ */

    private Map<String, Object> ok() {
        return Map.of("success", true);
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private int clampDelay(int seconds) {
        if (seconds < 0) return 0;
        if (seconds > 600) return 600;
        return seconds;
    }

    private String parseVoicesFromError(String errorMsg) {
        if (errorMsg == null) return null;
        Pattern pattern = Pattern.compile("(?i)available[^:]*:\\s*\\[([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(errorMsg);
        if (matcher.find()) {
            String raw = matcher.group(1);
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
