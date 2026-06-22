package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class TitleGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerationService.class);

    private final ChapterRepository chapterRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;

    public TitleGenerationService(ChapterRepository chapterRepository,
                                  AiProviderRouter providerRouter,
                                  PromptTemplateRegistry promptRegistry,
                                  AiUsageTracker aiUsageTracker) {
        this.chapterRepository = chapterRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
    }

    public void generateAndSaveTitles(Long projectId) {
        generateAndSaveTitles(projectId, () -> false);
    }

    public void generateAndSaveTitles(Long projectId, BooleanSupplier shouldStop) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHAPTER_WRITING);

        for (ChapterEntity ch : chapters) {
            if (shouldStop.getAsBoolean()) return;
            if (ch.getContent() == null || ch.getContent().isBlank()) continue;
            if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
                log.info("Chapter {} already has title '{}', skipping", ch.getChapterNumber(), ch.getTitle());
                continue;
            }

            String contentPreview = ch.getContent().length() > 1000
                    ? ch.getContent().substring(0, 1000) : ch.getContent();
            String titleTemplate = promptRegistry.getSubStepTemplate(WorkflowStep.POLISHING, PromptSubStep.CHAPTER_TITLE, null);
            String titlePrompt = promptRegistry.resolveTemplate(titleTemplate, Map.of("contentPreview", contentPreview));
            String titleSysPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.POLISHING, PromptSubStep.CHAPTER_TITLE, null);
            if (titleSysPrompt == null || titleSysPrompt.isBlank()) {
                titleSysPrompt = "你是一位小说编辑，擅长给章节起标题。要求每个标题控制在4-12个字，风格统一，长度尽量一致（建议6-8字）。只输出标题文字，不要任何额外内容。";
            }

            AiRequest request = AiRequest.builder()
                    .systemPrompt(titleSysPrompt)
                    .userPrompt(titlePrompt)
                    .maxTokens(30)
                    .temperature(0.5)
                    .build();
            applyResolvedConfig(request, resolved);

            try {
                StringBuilder title = new StringBuilder();
                long titleStart = System.currentTimeMillis();
                resolved.provider().streamText(request)
                        .doOnNext(title::append)
                        .blockLast();
                aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - titleStart);

                String generatedTitle = stripAiFormatting(title.toString().trim())
                        .replaceAll("[\"'\\n\\r]", "")
                        .replaceAll("[\u201c\u201d\u2018\u2019]", "");
                if (generatedTitle.length() > 12) {
                    generatedTitle = generatedTitle.substring(0, 12);
                }
                ch.setTitle(generatedTitle);
                chapterRepository.save(ch);
                log.info("Generated title for chapter {}: {}", ch.getChapterNumber(), generatedTitle);
            } catch (Exception e) {
                log.warn("Failed to generate title for chapter {}, skipping: {}", ch.getChapterNumber(), e.getMessage());
            }
        }
    }
}
