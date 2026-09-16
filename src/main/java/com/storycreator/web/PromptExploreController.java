package com.storycreator.web;

import org.springframework.beans.factory.annotation.Autowired;

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
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.entity.SideStoryChapterEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

@Controller
@RequestMapping("/prompts/explore")
public class PromptExploreController {

    private static final Logger log = LoggerFactory.getLogger(PromptExploreController.class);

    private PromptExploreService exploreService;
    private PromptTemplateRegistry promptRegistry;
    private BuiltinTemplateLoader builtinLoader;
    private PromptTemplateRepository promptTemplateRepository;
    private ProjectRepository projectRepository;
    private ChapterRepository chapterRepository;
    private ChapterOutlineRepository chapterOutlineRepository;
    private CharacterRepository characterRepository;
    private AiModelConfigRepository modelConfigRepository;
    private AiProviderRouter aiProviderRouter;
    private TtsProviderRegistry ttsProviderRegistry;
    private ImageProviderRegistry imageProviderRegistry;
    private SideStoryRepository sideStoryRepository;
    private SideStoryChapterRepository sideStoryChapterRepository;

    @Autowired
    public void setExploreService(PromptExploreService exploreService) {
        this.exploreService = exploreService;
    }

    @Autowired
    public void setPromptRegistry(PromptTemplateRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @Autowired
    public void setBuiltinLoader(BuiltinTemplateLoader builtinLoader) {
        this.builtinLoader = builtinLoader;
    }

    @Autowired
    public void setPromptTemplateRepository(PromptTemplateRepository promptTemplateRepository) {
        this.promptTemplateRepository = promptTemplateRepository;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setChapterOutlineRepository(ChapterOutlineRepository chapterOutlineRepository) {
        this.chapterOutlineRepository = chapterOutlineRepository;
    }

    @Autowired
    public void setCharacterRepository(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    @Autowired
    public void setModelConfigRepository(AiModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    @Autowired
    public void setAiProviderRouter(AiProviderRouter aiProviderRouter) {
        this.aiProviderRouter = aiProviderRouter;
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
    public void setSideStoryRepository(SideStoryRepository sideStoryRepository) {
        this.sideStoryRepository = sideStoryRepository;
    }

    @Autowired
    public void setSideStoryChapterRepository(SideStoryChapterRepository sideStoryChapterRepository) {
        this.sideStoryChapterRepository = sideStoryChapterRepository;
    }


    /**
     * 提示词探索页：转发到静态页。
     * 引导数据由 {@link #exploreData(String, Long)} 以 JSON 提供。
     */
    @GetMapping
    public String explorePage(@RequestParam(required = false) String templateKey,
                             @RequestParam(required = false) Long templateId) {
        return "forward:/pages/prompt-explore.html";
    }

    /** 提示词探索页的引导数据（模板原文/System Prompt + 变量提示 + 项目/模型下拉）。 */
    @GetMapping("/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> exploreData(
            @RequestParam(required = false) String templateKey,
            @RequestParam(required = false) Long templateId) {
        String templateContent = "";
        String systemPromptContent = "";
        WorkflowStep step = null;
        PromptSubStep subStep = null;
        String templateName = "";

        if (templateKey != null && !templateKey.isEmpty()) {
            BuiltinTemplate bt = builtinLoader.getAll().stream()
                    .filter(t -> t.key().equals(templateKey))
                    .findFirst()
                    .orElse(null);
            if (bt == null) {
                return ResponseEntity.notFound().build();
            }
            templateContent = bt.template() != null ? bt.template() : "";
            systemPromptContent = bt.systemPrompt() != null ? bt.systemPrompt() : "";
            step = bt.step();
            subStep = bt.subStep();
            templateName = bt.name();
        } else if (templateId != null) {
            PromptTemplateEntity entity = promptTemplateRepository.findById(templateId).orElse(null);
            if (entity == null) {
                return ResponseEntity.notFound().build();
            }
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

        List<Map<String, Object>> projects = new ArrayList<>();
        projectRepository.findAllByOrderByUpdatedAtDesc().forEach(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("title", p.getTitle());
            projects.add(item);
        });

        List<Map<String, Object>> modelConfigs = new ArrayList<>();
        for (AiModelConfigEntity c : modelConfigRepository.findByActiveTrue()) {
            if (c.getModelType() != com.storycreator.core.domain.ModelType.TEXT) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("displayName", c.getDisplayName());
            item.put("modelId", c.getModelId());
            modelConfigs.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateContent", templateContent);
        data.put("systemPromptContent", systemPromptContent);
        data.put("step", step != null ? step.name() : null);
        data.put("stepDisplayName", step != null ? step.getDisplayName() : null);
        data.put("subStep", subStep != null ? subStep.name() : null);
        data.put("subStepDisplayName", subStep != null ? subStep.getDisplayName() : null);
        data.put("templateName", templateName);
        data.put("variableNames", variableNames);
        data.put("projects", projects);
        data.put("modelConfigs", modelConfigs);
        data.put("templateKey", templateKey);
        data.put("templateId", templateId);
        return ResponseEntity.ok(data);
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

    @GetMapping("/chapter-outlines")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getChapterOutlines(@RequestParam Long projectId) {
        List<ChapterOutlineEntity> outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChapterOutlineEntity o : outlines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterNumber", o.getChapterNumber());
            item.put("title", o.getTitle() != null ? o.getTitle() : "第" + o.getChapterNumber() + "章");
            item.put("volumeNumber", o.getVolumeNumber());
            result.add(item);
        }
        int chaptersPerVolume = projectRepository.findById(projectId)
                .map(p -> p.getChaptersPerVolume())
                .orElse(10);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chaptersPerVolume", chaptersPerVolume);
        response.put("outlines", result);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/characters")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCharacters(@RequestParam Long projectId) {
        List<CharacterEntity> characters = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        List<Map<String, Object>> list = new ArrayList<>();
        for (CharacterEntity c : characters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName() != null ? c.getName() : "角色" + c.getSortOrder());
            item.put("sortOrder", c.getSortOrder());
            list.add(item);
        }
        int totalCards = projectRepository.findById(projectId)
                .map(p -> p.getCharacterCount())
                .orElse(5);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("characters", list);
        response.put("totalCards", totalCards);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/side-stories")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getSideStories(@RequestParam Long projectId) {
        List<SideStoryEntity> stories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SideStoryEntity s : stories) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("title", s.getTitle());
            item.put("status", s.getStatus());
            item.put("hasOutline", s.getOutline() != null && !s.getOutline().isBlank());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/side-story-chapters")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getSideStoryChapters(@RequestParam Long sideStoryId) {
        List<SideStoryChapterEntity> chapters = sideStoryChapterRepository
                .findBySideStoryIdOrderByChapterNumber(sideStoryId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SideStoryChapterEntity ch : chapters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapterNumber", ch.getChapterNumber());
            item.put("title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getChapterNumber() + "章");
            item.put("hasOutline", ch.getOutlineSummary() != null && !ch.getOutlineSummary().isBlank());
            item.put("hasContent", ch.getContent() != null && !ch.getContent().isBlank());
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
            Long templateId = toLong(body.get("templateId"));
            Long sideStoryId = toLong(body.get("sideStoryId"));
            Integer sideStoryChapterNumber = toInt(body.get("sideStoryChapterNumber"));

            if (projectId == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "请选择项目"));
            }

            WorkflowStep step = stepStr != null ? WorkflowStep.valueOf(stepStr) : null;
            PromptSubStep subStep = (subStepStr != null && !subStepStr.isEmpty()) ? PromptSubStep.valueOf(subStepStr) : null;

            PromptExploreContext exploreCtx = new PromptExploreContext();
            exploreCtx.setProjectId(projectId);
            exploreCtx.setChapterNumber(chapterNumber);
            exploreCtx.setCharacterId(characterId);
            exploreCtx.setCardNumber(cardNumber);
            exploreCtx.setTotalCards(totalCards);
            exploreCtx.setVolumeNumber(volumeNumber);
            exploreCtx.setTemplateId(templateId);
            exploreCtx.setSideStoryId(sideStoryId);
            exploreCtx.setSideStoryChapterNumber(sideStoryChapterNumber);
            PromptExploreService.ExploreResult result = exploreService.resolve(step, subStep, exploreCtx);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("renderedPrompt", result.renderedPrompt());
            response.put("systemPrompt", result.systemPrompt());
            response.put("variables", result.variables());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Prompt explore resolve error", e);
            return ResponseEntity.ok(Map.of("success", false, "error", SseErrorHelper.sanitize(e)));
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
                                emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
                                emitter.complete();
                            } catch (Exception ignored) {}
                        })
                        .blockLast();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(SseErrorHelper.sanitize(e)));
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
            return ResponseEntity.ok(Map.of("success", false, "error", SseErrorHelper.sanitize(e)));
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
