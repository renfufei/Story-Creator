package com.storycreator.web;

import com.storycreator.ai.prompt.BuiltinTemplate;
import com.storycreator.ai.prompt.BuiltinTemplateLoader;
import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.port.image.ImageRequest;
import com.storycreator.core.port.image.ImageResult;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

@Controller
@RequestMapping("/prompts/explore")
public class PromptExploreController {

    private static final Logger log = LoggerFactory.getLogger(PromptExploreController.class);

    private final PromptExploreService exploreService;
    private final PromptTemplateRegistry promptRegistry;
    private final BuiltinTemplateLoader builtinLoader;
    private final PromptTemplateRepository promptTemplateRepository;
    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final CharacterRepository characterRepository;
    private final AiModelConfigRepository modelConfigRepository;
    private final AiProviderRouter aiProviderRouter;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final ImageProviderRegistry imageProviderRegistry;

    public PromptExploreController(PromptExploreService exploreService,
                                   PromptTemplateRegistry promptRegistry,
                                   BuiltinTemplateLoader builtinLoader,
                                   PromptTemplateRepository promptTemplateRepository,
                                   ProjectRepository projectRepository,
                                   ChapterRepository chapterRepository,
                                   CharacterRepository characterRepository,
                                   AiModelConfigRepository modelConfigRepository,
                                   AiProviderRouter aiProviderRouter,
                                   TtsProviderRegistry ttsProviderRegistry,
                                   ImageProviderRegistry imageProviderRegistry) {
        this.exploreService = exploreService;
        this.promptRegistry = promptRegistry;
        this.builtinLoader = builtinLoader;
        this.promptTemplateRepository = promptTemplateRepository;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.characterRepository = characterRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.aiProviderRouter = aiProviderRouter;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.imageProviderRegistry = imageProviderRegistry;
    }

    @GetMapping
    public String explorePage(@RequestParam(required = false) String templateKey,
                             @RequestParam(required = false) Long templateId,
                             Model model) {
        String templateContent = "";
        String systemPromptContent = "";
        WorkflowStep step = null;
        PromptSubStep subStep = null;
        String templateName = "";

        if (templateKey != null && !templateKey.isEmpty()) {
            BuiltinTemplate bt = builtinLoader.getAll().stream()
                    .filter(t -> t.key().equals(templateKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Builtin template not found: " + templateKey));
            templateContent = bt.template() != null ? bt.template() : "";
            systemPromptContent = bt.systemPrompt() != null ? bt.systemPrompt() : "";
            step = bt.step();
            subStep = bt.subStep();
            templateName = bt.name();
        } else if (templateId != null) {
            PromptTemplateEntity entity = promptTemplateRepository.findById(templateId)
                    .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
            templateContent = entity.getTemplate() != null ? entity.getTemplate() : "";
            systemPromptContent = entity.getSystemPrompt() != null ? entity.getSystemPrompt() : "";
            step = entity.getStep();
            subStep = entity.getSubStep();
            templateName = entity.getName();
        }

        List<String> variableNames = List.of();
        if (subStep != null && PromptTemplateRegistry.SUB_STEP_VARIABLES.containsKey(subStep)) {
            variableNames = PromptTemplateRegistry.SUB_STEP_VARIABLES.get(subStep);
        }

        model.addAttribute("templateContent", templateContent);
        model.addAttribute("systemPromptContent", systemPromptContent);
        model.addAttribute("step", step);
        model.addAttribute("subStep", subStep);
        model.addAttribute("templateName", templateName);
        model.addAttribute("variableNames", variableNames);
        model.addAttribute("projects", projectRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("modelConfigs", modelConfigRepository.findByActiveTrue());
        model.addAttribute("templateKey", templateKey);
        model.addAttribute("templateId", templateId);

        return "prompt-explore";
    }

    @GetMapping("/chapters")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getChapters(@RequestParam Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChapterEntity ch : chapters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterNumber", ch.getChapterNumber());
            item.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getChapterNumber() + "章");
            item.put("hasContent", ch.getContent() != null && !ch.getContent().isBlank());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/characters")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getCharacters(@RequestParam Long projectId) {
        List<CharacterEntity> characters = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CharacterEntity c : characters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName() != null ? c.getName() : "角色" + c.getSortOrder());
            item.put("sortOrder", c.getSortOrder());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/resolve")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody Map<String, Object> body) {
        try {
            String stepStr = (String) body.get("step");
            String subStepStr = (String) body.get("subStep");
            Long projectId = toLong(body.get("projectId"));
            Integer chapterNumber = toInt(body.get("chapterNumber"));
            Long characterId = toLong(body.get("characterId"));
            Integer cardNumber = toInt(body.get("cardNumber"));
            Integer totalCards = toInt(body.get("totalCards"));
            Integer volumeNumber = toInt(body.get("volumeNumber"));

            if (projectId == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "请选择项目"));
            }

            WorkflowStep step = stepStr != null ? WorkflowStep.valueOf(stepStr) : null;
            PromptSubStep subStep = (subStepStr != null && !subStepStr.isEmpty()) ? PromptSubStep.valueOf(subStepStr) : null;

            PromptExploreService.ExploreResult result = exploreService.resolve(
                    step, subStep, projectId, chapterNumber, characterId, cardNumber, totalCards, volumeNumber);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("renderedPrompt", result.renderedPrompt());
            response.put("systemPrompt", result.systemPrompt());
            response.put("variables", result.variables());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Prompt explore resolve error", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "解析失败"));
        }
    }

    @PostMapping(value = "/call-ai-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter callAiStream(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String renderedPrompt = (String) body.get("renderedPrompt");
        String systemPrompt = (String) body.get("systemPrompt");
        Long configId = toLong(body.get("configId"));

        if (configId == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("请选择模型"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        Thread.startVirtualThread(() -> {
            try {
                AiModelConfigEntity config = modelConfigRepository.findById(configId)
                        .orElseThrow(() -> new IllegalArgumentException("Model config not found: " + configId));

                if (config.getModelType() != com.storycreator.core.domain.ModelType.TEXT) {
                    emitter.send(SseEmitter.event().name("error").data("仅支持TEXT模型"));
                    emitter.complete();
                    return;
                }

                AiProviderRouter.ResolvedModel resolved = aiProviderRouter.resolveModelByConfigId(configId);
                if (resolved == null) {
                    emitter.send(SseEmitter.event().name("error").data("无法解析模型配置"));
                    emitter.complete();
                    return;
                }

                AiRequest request = AiRequest.builder()
                        .systemPrompt(systemPrompt)
                        .userPrompt(renderedPrompt)
                        .maxTokens(4096)
                        .temperature(0.7)
                        .build();
                applyResolvedConfig(request, resolved);

                resolved.provider().streamText(request)
                        .doOnNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception e) {
                                // client disconnected
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        })
                        .doOnError(e -> {
                            try {
                                emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "生成失败"));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        })
                        .blockLast();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "调用失败"));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });

        return emitter;
    }

    @PostMapping("/call-ai")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> callAi(@RequestBody Map<String, Object> body) {
        try {
            String renderedPrompt = (String) body.get("renderedPrompt");
            String systemPrompt = (String) body.get("systemPrompt");
            Long configId = toLong(body.get("configId"));
            String voice = (String) body.get("voice");
            Double speed = body.get("speed") != null ? ((Number) body.get("speed")).doubleValue() : 1.0;

            if (configId == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "请选择模型"));
            }

            AiModelConfigEntity config = modelConfigRepository.findById(configId)
                    .orElseThrow(() -> new IllegalArgumentException("Model config not found: " + configId));

            Map<String, Object> response = new LinkedHashMap<>();

            switch (config.getModelType()) {
                case TEXT -> {
                    AiProviderRouter.ResolvedModel resolved = aiProviderRouter.resolveModelByConfigId(configId);
                    if (resolved == null) {
                        return ResponseEntity.ok(Map.of("success", false, "error", "无法解析TEXT模型配置"));
                    }
                    AiRequest request = AiRequest.builder()
                            .systemPrompt(systemPrompt)
                            .userPrompt(renderedPrompt)
                            .maxTokens(4096)
                            .temperature(0.7)
                            .build();
                    applyResolvedConfig(request, resolved);

                    StringBuilder result = new StringBuilder();
                    resolved.provider().streamText(request)
                            .doOnNext(result::append)
                            .blockLast();

                    response.put("success", true);
                    response.put("result", result.toString());
                    response.put("resultType", "text");
                }
                case TTS -> {
                    TtsProviderRegistry.ResolvedTtsConfig ttsConfig = ttsProviderRegistry.resolve(configId);
                    if (ttsConfig == null) {
                        return ResponseEntity.ok(Map.of("success", false, "error", "无法解析TTS模型配置"));
                    }
                    TtsRequest ttsRequest = new TtsRequest(
                            ttsConfig.modelId(),
                            renderedPrompt,
                            voice != null ? voice : "alloy",
                            "mp3",
                            speed,
                            ttsConfig.baseUrl(),
                            ttsConfig.apiKey()
                    );
                    byte[] audio = ttsConfig.provider().generateAudio(ttsRequest);
                    String audioBase64 = Base64.getEncoder().encodeToString(audio);

                    response.put("success", true);
                    response.put("audioBase64", audioBase64);
                    response.put("resultType", "audio");
                }
                case IMAGE -> {
                    ImageProviderRegistry.ResolvedImageConfig imageConfig = imageProviderRegistry.resolve(configId);
                    if (imageConfig == null) {
                        return ResponseEntity.ok(Map.of("success", false, "error", "无法解析IMAGE模型配置"));
                    }
                    ImageRequest imageRequest = ImageRequest.builder()
                            .model(imageConfig.modelId())
                            .prompt(renderedPrompt)
                            .imageType(ImageType.AVATAR)
                            .width(1024)
                            .height(1024)
                            .baseUrl(imageConfig.baseUrl())
                            .apiKey(imageConfig.apiKey())
                            .extraParams(imageConfig.extraParams())
                            .build();
                    ImageResult imageResult = imageConfig.provider().generateImage(imageRequest);
                    String imageBase64 = Base64.getEncoder().encodeToString(imageResult.imageBytes());

                    response.put("success", true);
                    response.put("imageBase64", imageBase64);
                    response.put("resultType", "image");
                }
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Prompt explore call-ai error", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "调用失败"));
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Integer toInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }
}
