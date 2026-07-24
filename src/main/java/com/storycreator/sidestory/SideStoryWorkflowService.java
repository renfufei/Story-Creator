package com.storycreator.sidestory;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.AiProviderRouter.ResolvedModel;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SideStoryWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(SideStoryWorkflowService.class);

    private final SideStoryRepository sideStoryRepository;
    private final SideStoryChapterRepository sideStoryChapterRepository;
    private final ProjectRepository projectRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry templateRegistry;
    private final JdbcTemplate jdbcTemplate;

    public SideStoryWorkflowService(SideStoryRepository sideStoryRepository,
                                     SideStoryChapterRepository sideStoryChapterRepository,
                                     ProjectRepository projectRepository,
                                     WorldSettingRepository worldSettingRepository,
                                     CharacterRepository characterRepository,
                                     StoryOutlineRepository storyOutlineRepository,
                                     VolumeOutlineRepository volumeOutlineRepository,
                                     AiProviderRouter providerRouter,
                                     PromptTemplateRegistry templateRegistry,
                                     JdbcTemplate jdbcTemplate) {
        this.sideStoryRepository = sideStoryRepository;
        this.sideStoryChapterRepository = sideStoryChapterRepository;
        this.projectRepository = projectRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.providerRouter = providerRouter;
        this.templateRegistry = templateRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== Generate Outline ====================

    public Flux<String> generateOutline(Long projectId, Long sideStoryId) {
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + sideStoryId));
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        Map<String, String> variables = buildOutlineVariables(project, sideStory);
        return streamGeneration(projectId, WorkflowStep.CHAPTER_WRITING, PromptSubStep.SIDE_STORY_OUTLINE, project, variables);
    }

    public Map<String, String> buildOutlineVariables(ProjectEntity project, SideStoryEntity sideStory) {
        Long projectId = project.getId();
        Map<String, String> vars = new LinkedHashMap<>();

        vars.put("worldSetting", worldSettingRepository.findByProjectId(projectId)
                .map(WorldSettingEntity::getContent).orElse(""));
        vars.put("allCharacters", buildCharactersForSideStory(sideStory));
        vars.put("storySummary", storyOutlineRepository.findByProjectId(projectId)
                .map(StoryOutlineEntity::getContent).orElse(""));
        vars.put("sideStoryTitle", sideStory.getTitle());
        vars.put("sideStoryDescription", sideStory.getDescription() != null ? sideStory.getDescription() : "");
        vars.put("creativeGuidance", sideStory.getCreativeGuidance() != null ? sideStory.getCreativeGuidance() : "");
        vars.put("attachedVolumeContext", buildAttachedVolumeContext(projectId, sideStory.getAttachedVolume()));

        return vars;
    }

    public void saveOutline(Long sideStoryId, String content) {
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + sideStoryId));
        sideStory.setOutline(content);
        sideStory.setStatus("OUTLINE_READY");

        // Parse arcName from first line (pattern: "故事弧线：xxx")
        String arcName = extractArcName(content);
        if (arcName != null) {
            sideStory.setArcName(arcName);
        }

        sideStoryRepository.save(sideStory);
    }

    // ==================== Generate Chapter Outline ====================

    public Flux<String> generateChapterOutline(Long projectId, Long sideStoryId, int chapterNumber) {
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + sideStoryId));
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        Map<String, String> variables = buildChapterOutlineVariables(project, sideStory, chapterNumber);
        return streamGeneration(projectId, WorkflowStep.CHAPTER_WRITING, PromptSubStep.SIDE_STORY_CHAPTER_OUTLINE, project, variables);
    }

    public Map<String, String> buildChapterOutlineVariables(ProjectEntity project, SideStoryEntity sideStory, int chapterNumber) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("sideStoryTitle", sideStory.getTitle());
        vars.put("sideStoryOutline", sideStory.getOutline() != null ? sideStory.getOutline() : "");
        vars.put("allCharacters", buildCharactersForSideStory(sideStory));

        int totalChapters = sideStoryChapterRepository.countBySideStoryId(sideStory.getId());
        vars.put("chapterNumber", String.valueOf(chapterNumber));
        vars.put("totalChapters", String.valueOf(totalChapters));

        // Previous chapter outlines
        List<SideStoryChapterEntity> chapters = sideStoryChapterRepository
                .findBySideStoryIdOrderByChapterNumber(sideStory.getId());
        StringBuilder prevOutlines = new StringBuilder();
        for (SideStoryChapterEntity ch : chapters) {
            if (ch.getChapterNumber() >= chapterNumber) break;
            prevOutlines.append("第").append(ch.getChapterNumber()).append("章");
            if (ch.getTitle() != null) prevOutlines.append(" ").append(ch.getTitle());
            prevOutlines.append("\n");
            if (ch.getOutlineSummary() != null) {
                prevOutlines.append(ch.getOutlineSummary()).append("\n\n");
            }
        }
        vars.put("previousOutlines", prevOutlines.toString());

        return vars;
    }

    public void saveChapterOutline(Long sideStoryId, int chapterNumber, String content) {
        SideStoryChapterEntity chapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + sideStoryId + "/" + chapterNumber));

        // Extract title from content (first line if it looks like a title)
        String title = extractTitleFromOutline(content);
        if (title != null) {
            chapter.setTitle(title);
        }
        chapter.setOutlineSummary(content);
        chapter.setStatus("NOT_STARTED");
        sideStoryChapterRepository.save(chapter);
    }

    // ==================== Generate Chapter Content ====================

    public Flux<String> generateChapterContent(Long projectId, Long sideStoryId, int chapterNumber) {
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + sideStoryId));
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Set status to GENERATING
        SideStoryChapterEntity chapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        chapter.setStatus("GENERATING");
        sideStoryChapterRepository.save(chapter);

        Map<String, String> variables = buildWritingVariables(project, sideStory, chapterNumber);
        return streamGeneration(projectId, WorkflowStep.CHAPTER_WRITING, PromptSubStep.SIDE_STORY_WRITING, project, variables);
    }

    public Map<String, String> buildWritingVariables(ProjectEntity project, SideStoryEntity sideStory, int chapterNumber) {
        Map<String, String> vars = new LinkedHashMap<>();

        vars.put("characterCards", buildCharacterCardsForSideStory(sideStory));
        vars.put("sideStoryTitle", sideStory.getTitle());
        vars.put("sideStoryOutline", sideStory.getOutline() != null ? sideStory.getOutline() : "");

        SideStoryChapterEntity currentChapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStory.getId(), chapterNumber)
                .orElseThrow();
        vars.put("chapterNumber", String.valueOf(chapterNumber));
        vars.put("chapterTitle", currentChapter.getTitle() != null ? currentChapter.getTitle() : "");
        vars.put("chapterSummary", currentChapter.getOutlineSummary() != null ? currentChapter.getOutlineSummary() : "");

        // Previous context (last chapter's outline summary)
        sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(sideStory.getId(), chapterNumber - 1)
                .ifPresentOrElse(
                        prev -> vars.put("previousContext", prev.getOutlineSummary() != null ? prev.getOutlineSummary() : "无前文"),
                        () -> vars.put("previousContext", "无前文（这是第一章）")
                );

        vars.put("chapterWordCount", String.valueOf(project.getChapterWordCount()));

        return vars;
    }

    public void saveChapterContent(Long sideStoryId, int chapterNumber, String content) {
        SideStoryChapterEntity chapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + sideStoryId + "/" + chapterNumber));
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus("COMPLETED");
        sideStoryChapterRepository.save(chapter);

        // Update side story status to IN_PROGRESS
        SideStoryEntity sideStory = sideStoryRepository.findById(sideStoryId).orElse(null);
        if (sideStory != null && "OUTLINE_READY".equals(sideStory.getStatus())) {
            sideStory.setStatus("IN_PROGRESS");
            sideStoryRepository.save(sideStory);
        }
    }

    // ==================== Polish Chapter ====================

    public Flux<String> polishChapter(Long projectId, Long sideStoryId, int chapterNumber) {
        SideStoryChapterEntity chapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        chapter.setStatus("POLISHING");
        sideStoryChapterRepository.save(chapter);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", project.getTitle());
        vars.put("genre", project.getGenre().getDisplayName());
        vars.put("content", chapter.getContent() != null ? chapter.getContent() : "");
        vars.put("polishNote", "");
        vars.put("stepGuidance", "");
        vars.put("referenceMaterials", "");

        // Polish uses POLISHING step model
        ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.POLISHING);
        String template = templateRegistry.getSubStepTemplate(
                WorkflowStep.POLISHING, PromptSubStep.POLISHING_PRIMARY, project.getGenre());
        String systemPrompt = templateRegistry.getSubStepSystemPrompt(
                WorkflowStep.POLISHING, PromptSubStep.POLISHING_PRIMARY, project.getGenre());
        String prompt = templateRegistry.resolveTemplate(template, vars);

        AiRequest request = AiRequest.builder()
                .model(resolved.modelId())
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(16384)
                .baseUrl(resolved.baseUrl())
                .apiKey(resolved.apiKey())
                .extraParams(resolved.extraParams())
                .build();

        return resolved.provider().streamText(request);
    }

    public void savePolishedContent(Long sideStoryId, int chapterNumber, String content) {
        SideStoryChapterEntity chapter = sideStoryChapterRepository
                .findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found"));
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setStatus("POLISHED");
        sideStoryChapterRepository.save(chapter);
    }

    // ==================== Status Reset ====================

    public void resetGeneratingStatus(Long sideStoryId, int chapterNumber) {
        sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .ifPresent(ch -> {
                    if ("GENERATING".equals(ch.getStatus()) || "POLISHING".equals(ch.getStatus())) {
                        ch.setStatus("NOT_STARTED".equals(ch.getStatus()) ? "NOT_STARTED" :
                                (ch.getContent() != null && !ch.getContent().isBlank()) ? "COMPLETED" : "NOT_STARTED");
                        sideStoryChapterRepository.save(ch);
                    }
                });
    }

    public void resetChapterStatus(Long sideStoryId, int chapterNumber, String fallbackStatus) {
        sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(sideStoryId, chapterNumber)
                .ifPresent(ch -> {
                    ch.setStatus(fallbackStatus);
                    sideStoryChapterRepository.save(ch);
                });
    }

    // ==================== Character Association ====================

    public List<Long> getCharacterIds(Long sideStoryId) {
        return jdbcTemplate.queryForList(
                "SELECT character_id FROM side_story_characters WHERE side_story_id = ?",
                Long.class, sideStoryId);
    }

    public void setCharacterIds(Long sideStoryId, List<Long> characterIds) {
        jdbcTemplate.update("DELETE FROM side_story_characters WHERE side_story_id = ?", sideStoryId);
        if (characterIds != null) {
            for (Long charId : characterIds) {
                jdbcTemplate.update(
                        "INSERT INTO side_story_characters (side_story_id, character_id) VALUES (?, ?)",
                        sideStoryId, charId);
            }
        }
    }

    // ==================== Private Helpers ====================

    private Flux<String> streamGeneration(Long projectId, WorkflowStep step, PromptSubStep subStep,
                                           ProjectEntity project, Map<String, String> variables) {
        ResolvedModel resolved = providerRouter.resolveModel(projectId, step);

        String template = templateRegistry.getSubStepTemplate(step, subStep, project.getGenre());
        String systemPrompt = templateRegistry.getSubStepSystemPrompt(step, subStep, project.getGenre());
        String prompt = templateRegistry.resolveTemplate(template, variables);

        AiRequest request = AiRequest.builder()
                .model(resolved.modelId())
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(16384)
                .baseUrl(resolved.baseUrl())
                .apiKey(resolved.apiKey())
                .extraParams(resolved.extraParams())
                .build();

        return resolved.provider().streamText(request);
    }

    private String buildCharactersForSideStory(SideStoryEntity sideStory) {
        List<Long> charIds = getCharacterIds(sideStory.getId());
        List<CharacterEntity> characters;
        if (charIds.isEmpty()) {
            characters = characterRepository.findByProjectIdOrderBySortOrder(sideStory.getProjectId());
        } else {
            characters = characterRepository.findAllById(charIds);
        }
        return characters.stream()
                .map(c -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("【").append(c.getName() != null ? c.getName() : "未命名").append("】");
                    if (c.getSummary() != null) sb.append("\n").append(c.getSummary());
                    else if (c.getContent() != null) sb.append("\n").append(c.getContent());
                    return sb.toString();
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String buildCharacterCardsForSideStory(SideStoryEntity sideStory) {
        List<Long> charIds = getCharacterIds(sideStory.getId());
        List<CharacterEntity> characters;
        if (charIds.isEmpty()) {
            characters = characterRepository.findByProjectIdOrderBySortOrder(sideStory.getProjectId());
        } else {
            characters = characterRepository.findAllById(charIds);
        }
        return characters.stream()
                .map(c -> {
                    if (c.getContent() != null) return c.getContent();
                    return c.getName() != null ? c.getName() : "";
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildAttachedVolumeContext(Long projectId, Integer attachedVolume) {
        if (attachedVolume == null) return "无特定关联卷";
        return volumeOutlineRepository.findByProjectIdAndVolumeNumber(projectId, attachedVolume)
                .map(v -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("关联卷: 第").append(v.getVolumeNumber()).append("卷");
                    if (v.getTitle() != null) sb.append(" ").append(v.getTitle());
                    sb.append("\n");
                    if (v.getArcSummary() != null) sb.append("卷弧线: ").append(v.getArcSummary());
                    return sb.toString();
                })
                .orElse("关联卷 " + attachedVolume + "（无详细信息）");
    }

    private String extractArcName(String content) {
        if (content == null || content.isBlank()) return null;
        String firstLine = content.lines().findFirst().orElse("");
        if (firstLine.startsWith("故事弧线") && (firstLine.contains("：") || firstLine.contains(":"))) {
            String name = firstLine.replaceAll("^[^：:]+[：:]\\s*", "").trim();
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    private String extractTitleFromOutline(String content) {
        if (content == null || content.isBlank()) return null;
        String firstLine = content.lines().findFirst().orElse("");
        // Patterns like "章节标题：xxx" or "## xxx" or just a short first line
        if (firstLine.startsWith("章节标题") || firstLine.startsWith("标题")) {
            String title = firstLine.replaceAll("^[^：:]+[：:]\\s*", "").trim();
            return title.isEmpty() ? null : title;
        }
        if (firstLine.startsWith("#")) {
            return firstLine.replaceAll("^#+\\s*", "").trim();
        }
        if (firstLine.length() <= 30 && !firstLine.contains("。")) {
            return firstLine.trim();
        }
        return null;
    }
}
