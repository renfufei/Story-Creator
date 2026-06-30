package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.service.CharacterStateDimensionService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterOutlineEntity;
import com.storycreator.persistence.repository.ChapterOutlineRepository;
import com.storycreator.persistence.repository.ChapterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class CharacterStateService {

    private static final Logger log = LoggerFactory.getLogger(CharacterStateService.class);

    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;
    private final CharacterStateDimensionService characterStateDimensionService;

    public CharacterStateService(ChapterRepository chapterRepository,
                                 ChapterOutlineRepository chapterOutlineRepository,
                                 AiProviderRouter providerRouter,
                                 PromptTemplateRegistry promptRegistry,
                                 AiUsageTracker aiUsageTracker,
                                 CharacterStateDimensionService characterStateDimensionService) {
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
        this.characterStateDimensionService = characterStateDimensionService;
    }

    public Map<String, String> buildCharacterStateVariables(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        String content = chapter.getContent() != null ? chapter.getContent() : "";
        String excerpt;
        if (content.length() > 3000) {
            excerpt = content.substring(0, 2000) + "\n...\n" + content.substring(content.length() - 1000);
        } else {
            excerpt = content;
        }

        String prevStates = "";
        if (chapterNumber > 1) {
            prevStates = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                    .map(ChapterEntity::getCharacterStates)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse("");
        }

        String charNames = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(ChapterOutlineEntity::getCharacterNames)
                .filter(s -> s != null && !s.isBlank())
                .orElse("");

        List<String> dims = characterStateDimensionService.getEnabledDisplayNames(projectId);
        String dimList = dims.isEmpty() ? "角色状态" : String.join("、", dims);

        return Map.of(
                "dimList", dimList,
                "charNames", charNames.isBlank() ? "" : "【本章出场角色】\n" + charNames + "\n",
                "prevStates", prevStates.isBlank() ? "" : "【前章角色状态（参考）】\n" + prevStates + "\n",
                "chapterExcerpt", excerpt
        );
    }

    public void generateCharacterStates(Long projectId, int chapterNumber) {
        generateCharacterStates(projectId, chapterNumber, null);
    }

    public void generateCharacterStates(Long projectId, int chapterNumber, Consumer<String> tokenSink) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).orElse(null);
        if (chapter == null || chapter.getContent() == null || chapter.getContent().isBlank()) return;

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHAPTER_WRITING);

        // Build content excerpt (first 2000 + last 1000 chars for long chapters)
        String content = chapter.getContent();
        String excerpt;
        if (content.length() > 3000) {
            excerpt = content.substring(0, 2000) + "\n...\n" + content.substring(content.length() - 1000);
        } else {
            excerpt = content;
        }

        // Load previous chapter states as reference
        String prevStates = "";
        if (chapterNumber > 1) {
            prevStates = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                    .map(ChapterEntity::getCharacterStates)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse("");
        }

        // Load character names from outline
        String charNames = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .map(ChapterOutlineEntity::getCharacterNames)
                .filter(s -> s != null && !s.isBlank())
                .orElse("");

        List<String> dims = characterStateDimensionService.getEnabledDisplayNames(projectId);
        String dimList = dims.isEmpty() ? "角色状态" : String.join("、", dims);

        String csTemplate = promptRegistry.getSubStepTemplate(WorkflowStep.POLISHING, PromptSubStep.CHARACTER_STATES, null);
        String userPrompt = promptRegistry.resolveTemplate(csTemplate, Map.of(
                "dimList", dimList,
                "charNames", charNames.isBlank() ? "" : "【本章出场角色】\n" + charNames + "\n",
                "prevStates", prevStates.isBlank() ? "" : "【前章角色状态（参考）】\n" + prevStates + "\n",
                "chapterExcerpt", excerpt));
        String csSysPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.POLISHING, PromptSubStep.CHARACTER_STATES, null);
        if (csSysPrompt == null || csSysPrompt.isBlank()) {
            csSysPrompt = "你是一位小说编辑助手，擅长从章节内容中提取角色状态变化。请简洁准确地汇总。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(csSysPrompt)
                .userPrompt(userPrompt)
                .maxTokens(500)
                .temperature(0.3)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder result = new StringBuilder();
        long start = System.currentTimeMillis();
        resolved.provider().streamText(request)
                .doOnNext(token -> {
                    result.append(token);
                    if (tokenSink != null) tokenSink.accept(token);
                })
                .blockLast();
        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - start);

        String states = stripAiFormatting(result.toString().trim());
        if (!states.isBlank()) {
            chapter.setCharacterStates(states);
            chapterRepository.save(chapter);
            log.info("[P{}] Generated character states for chapter {} ({}chars)", projectId, chapterNumber, states.length());
        }
    }
}
