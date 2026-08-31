package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;
import static com.storycreator.workflow.engine.TextProcessingUtils.stripAiFormatting;

@Service
public class ExpansionService {

    private static final Logger log = LoggerFactory.getLogger(ExpansionService.class);

    private final ChapterRepository chapterRepository;
    private final ProjectRepository projectRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;
    private final GlobalSettingService globalSettingService;

    public ExpansionService(ChapterRepository chapterRepository,
                            ProjectRepository projectRepository,
                            AiProviderRouter providerRouter,
                            PromptTemplateRegistry promptRegistry,
                            AiUsageTracker aiUsageTracker,
                            GlobalSettingService globalSettingService) {
        this.chapterRepository = chapterRepository;
        this.projectRepository = projectRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
        this.globalSettingService = globalSettingService;
    }

    public Flux<String> runBatchExpansion(Long projectId) {
        List<ChapterEntity> pendingChapters = chapterRepository
                .findByProjectIdAndExpansionStatusOrderByChapterNumber(projectId, "PENDING");

        if (pendingChapters.isEmpty()) {
            return Flux.just("[[EXPAND:DONE]]");
        }

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHAPTER_WRITING);

        return Flux.fromIterable(pendingChapters)
                .concatMap(chapter -> expandSingleChapter(chapter, project, resolved));
    }

    public Flux<String> expandSingleChapter(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHAPTER_WRITING);

        return expandSingleChapter(chapter, project, resolved);
    }

    private Flux<String> expandSingleChapter(ChapterEntity chapter, ProjectEntity project,
                                              AiProviderRouter.ResolvedModel resolved) {
        int chNum = chapter.getChapterNumber();
        Long projectId = chapter.getProjectId();
        String originalContent = chapter.getContent();

        if (originalContent == null || originalContent.isBlank()) {
            return Flux.empty();
        }

        // Backup original content and set status
        chapter.setContentBeforeExpansion(originalContent);
        chapter.setExpansionStatus("GENERATING");
        chapterRepository.save(chapter);

        // Build prompt
        String guidanceText = project.getExpansionGuidance();
        String guidanceSection = (guidanceText != null && !guidanceText.isBlank())
                ? "【扩写方向】\n" + guidanceText
                : "";

        String template = promptRegistry.getSubStepTemplate(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_EXPANSION, project.getGenre());
        String userPrompt = promptRegistry.resolveTemplate(template, Map.of(
                "title", project.getTitle(),
                "genre", project.getGenre() != null ? project.getGenre().name() : "",
                "chapterNumber", String.valueOf(chNum),
                "chapterTitle", chapter.getTitle() != null ? chapter.getTitle() : "",
                "originalContent", originalContent,
                "expansionGuidance", guidanceSection,
                "chapterWordCount", String.valueOf(originalContent.length())
        ));

        String systemPrompt = promptRegistry.getSubStepSystemPrompt(
                WorkflowStep.CHAPTER_WRITING, PromptSubStep.CHAPTER_EXPANSION, project.getGenre());

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(16384)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder expandedContent = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return Flux.just("[[EXPAND:CHAPTER:" + chNum + "]]")
                .concatWith(resolved.provider().streamText(request)
                        .doOnNext(expandedContent::append)
                        .doOnComplete(() -> {
                            aiUsageTracker.record(projectId, resolved.modelId(),
                                    resolved.provider().getProviderName(),
                                    System.currentTimeMillis() - startTime);
                            chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).ifPresent(ch -> {
                                String cleaned = stripAiFormatting(expandedContent.toString());
                                ch.setContent(cleaned);
                                ch.setWordCount(cleaned.length());
                                ch.setExpansionStatus("EXPANDED");
                                chapterRepository.save(ch);
                            });
                            log.info("[P{}] Expansion completed for chapter {}", projectId, chNum);
                        })
                        .doOnError(e -> {
                            chapterRepository.findByProjectIdAndChapterNumber(projectId, chNum).ifPresent(ch -> {
                                ch.setExpansionStatus("PENDING");
                                chapterRepository.save(ch);
                            });
                            log.error("[P{}] Expansion failed for chapter {}: {}", projectId, chNum, e.getMessage());
                        }));
    }
}
