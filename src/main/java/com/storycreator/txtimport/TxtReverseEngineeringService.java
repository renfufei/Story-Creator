package com.storycreator.txtimport;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.engine.AiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

@Service
public class TxtReverseEngineeringService {

    private static final Logger log = LoggerFactory.getLogger(TxtReverseEngineeringService.class);

    private final TxtImportJobRepository jobRepository;
    private final TxtImportChapterRepository importChapterRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;
    private final GlobalSettingService globalSettingService;

    public TxtReverseEngineeringService(TxtImportJobRepository jobRepository,
                                         TxtImportChapterRepository importChapterRepository,
                                         WorldSettingRepository worldSettingRepository,
                                         CharacterRepository characterRepository,
                                         StoryOutlineRepository storyOutlineRepository,
                                         AiProviderRouter providerRouter,
                                         PromptTemplateRegistry promptRegistry,
                                         AiUsageTracker aiUsageTracker,
                                         GlobalSettingService globalSettingService) {
        this.jobRepository = jobRepository;
        this.importChapterRepository = importChapterRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
        this.globalSettingService = globalSettingService;
    }

    public Flux<String> runReverseEngineering(Long jobId, Long projectId) {
        TxtImportJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId);
        String sampledChapters = sampleChapters(chapters, job.getSamplingStrategy(), job.getSamplingN());

        Genre genre = null;
        if (job.getGenre() != null && !job.getGenre().isBlank()) {
            try { genre = Genre.valueOf(job.getGenre()); } catch (Exception ignored) {}
        }

        AiProviderRouter.ResolvedModel resolved;
        if (job.getModelConfigId() != null) {
            resolved = providerRouter.resolveModelByConfigId(job.getModelConfigId());
        } else {
            resolved = providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);
        }

        final Genre finalGenre = genre;
        final String title = job.getTitle();
        final int chapterCount = chapters.size();

        Flux<String> flux = Flux.empty();

        if (job.isRunWorldBuilding()) {
            flux = flux.concatWith(Flux.just("[[RE_PHASE:WORLD]]"))
                    .concatWith(runWorldBuilding(job, projectId, title, finalGenre, chapterCount, sampledChapters, resolved));
        }

        if (job.isRunCharacters()) {
            flux = flux.concatWith(Flux.just("[[RE_PHASE:CHARS]]"))
                    .concatWith(Flux.defer(() -> runCharacterExtraction(job, projectId, title, finalGenre, chapterCount, sampledChapters, resolved)));
        }

        if (job.isRunOutline()) {
            flux = flux.concatWith(Flux.just("[[RE_PHASE:OUTLINE]]"))
                    .concatWith(Flux.defer(() -> runOutlineGeneration(job, projectId, title, finalGenre, chapterCount, sampledChapters, resolved)));
        }

        return flux;
    }

    private Flux<String> runWorldBuilding(TxtImportJobEntity job, Long projectId, String title,
                                           Genre genre, int chapterCount, String sampledChapters,
                                           AiProviderRouter.ResolvedModel resolved) {
        job.setStatus("RE_WORLD");
        job.setProgressNote("正在逆向世界观...");
        jobRepository.save(job);

        String template = promptRegistry.getSubStepTemplate(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.REVERSE_WORLD_BUILDING, genre);
        String userPrompt = promptRegistry.resolveTemplate(template, Map.of(
                "title", title,
                "genre", genre != null ? genre.getDisplayName() : "未指定",
                "sampledChapters", sampledChapters,
                "chapterCount", String.valueOf(chapterCount)
        ));
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(
                WorkflowStep.WORLD_BUILDING, PromptSubStep.REVERSE_WORLD_BUILDING, genre);

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(8192)
                .temperature(0.5)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder content = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return resolved.provider().streamText(request)
                .doOnNext(content::append)
                .doOnComplete(() -> {
                    aiUsageTracker.record(projectId, resolved.modelId(),
                            resolved.provider().getProviderName(),
                            System.currentTimeMillis() - startTime);

                    WorldSettingEntity ws = worldSettingRepository.findByProjectId(projectId)
                            .orElseGet(() -> {
                                WorldSettingEntity entity = new WorldSettingEntity();
                                entity.setProjectId(projectId);
                                return entity;
                            });
                    ws.setContent(content.toString());
                    worldSettingRepository.save(ws);
                    log.info("[Import:{}] World building reverse completed", job.getId());
                })
                .doOnError(e -> {
                    job.setStatus("FAILED");
                    job.setErrorMessage("世界观逆向失败: " + e.getMessage());
                    jobRepository.save(job);
                });
    }

    private Flux<String> runCharacterExtraction(TxtImportJobEntity job, Long projectId, String title,
                                                  Genre genre, int chapterCount, String sampledChapters,
                                                  AiProviderRouter.ResolvedModel resolved) {
        job.setStatus("RE_CHARS");
        job.setProgressNote("正在逆向角色...");
        jobRepository.save(job);

        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent)
                .orElse("暂无");

        String template = promptRegistry.getSubStepTemplate(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.REVERSE_CHARACTER_EXTRACTION, genre);
        String userPrompt = promptRegistry.resolveTemplate(template, Map.of(
                "title", title,
                "genre", genre != null ? genre.getDisplayName() : "未指定",
                "sampledChapters", sampledChapters,
                "chapterCount", String.valueOf(chapterCount),
                "worldSetting", worldSetting
        ));
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(
                WorkflowStep.CHARACTER_DESIGN, PromptSubStep.REVERSE_CHARACTER_EXTRACTION, genre);

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(8192)
                .temperature(0.5)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder content = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return resolved.provider().streamText(request)
                .doOnNext(content::append)
                .doOnComplete(() -> {
                    aiUsageTracker.record(projectId, resolved.modelId(),
                            resolved.provider().getProviderName(),
                            System.currentTimeMillis() - startTime);

                    // Save as a single character entity with the full extraction
                    CharacterEntity character = new CharacterEntity();
                    character.setProjectId(projectId);
                    character.setName("角色总览");
                    character.setContent(content.toString());
                    character.setStatus("GENERATED");
                    character.setSortOrder(0);
                    characterRepository.save(character);
                    log.info("[Import:{}] Character extraction completed", job.getId());
                })
                .doOnError(e -> {
                    job.setStatus("FAILED");
                    job.setErrorMessage("角色逆向失败: " + e.getMessage());
                    jobRepository.save(job);
                });
    }

    private Flux<String> runOutlineGeneration(TxtImportJobEntity job, Long projectId, String title,
                                                Genre genre, int chapterCount, String sampledChapters,
                                                AiProviderRouter.ResolvedModel resolved) {
        job.setStatus("RE_OUTLINE");
        job.setProgressNote("正在逆向大纲...");
        jobRepository.save(job);

        String worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent)
                .orElse("暂无");

        List<CharacterEntity> characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        String characterInfo = characters.stream()
                .map(c -> c.getContent() != null ? c.getContent() : c.getName())
                .collect(Collectors.joining("\n\n"));

        String template = promptRegistry.getSubStepTemplate(
                WorkflowStep.OUTLINE_GENERATION, PromptSubStep.REVERSE_OUTLINE_GENERATION, genre);
        String userPrompt = promptRegistry.resolveTemplate(template, Map.of(
                "title", title,
                "genre", genre != null ? genre.getDisplayName() : "未指定",
                "sampledChapters", sampledChapters,
                "chapterCount", String.valueOf(chapterCount),
                "worldSetting", worldSetting,
                "characters", characterInfo.isEmpty() ? "暂无" : characterInfo
        ));
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(
                WorkflowStep.OUTLINE_GENERATION, PromptSubStep.REVERSE_OUTLINE_GENERATION, genre);

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(8192)
                .temperature(0.5)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder content = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return resolved.provider().streamText(request)
                .doOnNext(content::append)
                .doOnComplete(() -> {
                    aiUsageTracker.record(projectId, resolved.modelId(),
                            resolved.provider().getProviderName(),
                            System.currentTimeMillis() - startTime);

                    StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                            .orElseGet(() -> {
                                StoryOutlineEntity entity = new StoryOutlineEntity();
                                entity.setProjectId(projectId);
                                return entity;
                            });
                    outline.setContent(content.toString());
                    outline.setTotalChapters(chapterCount);
                    storyOutlineRepository.save(outline);

                    // Mark job as done
                    job.setStatus("DONE");
                    job.setProgressNote("逆向工程完成");
                    jobRepository.save(job);
                    log.info("[Import:{}] Outline generation completed", job.getId());
                })
                .doOnError(e -> {
                    job.setStatus("FAILED");
                    job.setErrorMessage("大纲逆向失败: " + e.getMessage());
                    jobRepository.save(job);
                });
    }

    private String sampleChapters(List<TxtImportChapterEntity> chapters, String strategy, int n) {
        if (chapters.isEmpty()) return "";

        List<TxtImportChapterEntity> sampled;
        switch (strategy) {
            case "FIRST_N":
                sampled = chapters.subList(0, Math.min(n, chapters.size()));
                break;
            case "ALL":
                sampled = chapters;
                break;
            case "UNIFORM":
            default:
                sampled = uniformSample(chapters, n);
                break;
        }

        StringBuilder sb = new StringBuilder();
        for (TxtImportChapterEntity ch : sampled) {
            sb.append("--- 第").append(ch.getChapterNumber()).append("章");
            if (ch.getTitle() != null) sb.append(" ").append(ch.getTitle());
            sb.append(" ---\n");
            // Truncate very long chapters to ~3000 chars for context
            String content = ch.getContent();
            if (content != null && content.length() > 3000) {
                content = content.substring(0, 3000) + "\n...(省略)";
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    private List<TxtImportChapterEntity> uniformSample(List<TxtImportChapterEntity> chapters, int n) {
        if (chapters.size() <= n) return chapters;
        List<TxtImportChapterEntity> result = new ArrayList<>();
        double step = (double) chapters.size() / n;
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.round(i * step);
            if (idx >= chapters.size()) idx = chapters.size() - 1;
            result.add(chapters.get(idx));
        }
        return result;
    }
}
