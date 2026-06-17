package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.entity.ProofreadingReportEntity;
import com.storycreator.persistence.repository.*;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import com.storycreator.workflow.background.BackgroundGenerationService;
import com.storycreator.workflow.step.WorkflowStepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final Map<WorkflowStep, WorkflowStepHandler> handlers;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final ProjectRepository projectRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final BackgroundGenerationService backgroundGenerationService;
    private final ContextSummaryService contextSummaryService;
    private final AiUsageTracker aiUsageTracker;

    public WorkflowEngine(List<WorkflowStepHandler> handlerList,
                         AiProviderRouter providerRouter,
                         PromptTemplateRegistry promptRegistry,
                         ProjectRepository projectRepository,
                         WorkflowStateRepository workflowStateRepository,
                         WorldSettingRepository worldSettingRepository,
                         CharacterRepository characterRepository,
                         StoryOutlineRepository storyOutlineRepository,
                         ChapterOutlineRepository chapterOutlineRepository,
                         VolumeOutlineRepository volumeOutlineRepository,
                         ChapterRepository chapterRepository,
                         StepGuidanceRepository stepGuidanceRepository,
                         ProofreadingReportRepository proofreadingReportRepository,
                         @Lazy BackgroundGenerationService backgroundGenerationService,
                         ContextSummaryService contextSummaryService,
                         AiUsageTracker aiUsageTracker) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(WorkflowStepHandler::getStep, Function.identity()));
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.projectRepository = projectRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
        this.backgroundGenerationService = backgroundGenerationService;
        this.contextSummaryService = contextSummaryService;
        this.aiUsageTracker = aiUsageTracker;
    }

    public AiProviderRouter.ResolvedModel resolveModelForProject(Long projectId) {
        return providerRouter.resolveModel(projectId, WorkflowStep.POLISHING);
    }

    public VolumeOutlineRepository getVolumeOutlineRepository() {
        return volumeOutlineRepository;
    }

    public WorkflowContext buildContext(Long projectId) {
        return buildContext(projectId, 0);
    }

    public WorkflowContext buildContext(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        WorkflowContext context = new WorkflowContext();
        context.setProjectId(projectId);
        context.setTitle(project.getTitle());
        context.setGenre(project.getGenre());
        context.setDescription(project.getDescription());
        context.setCurrentStep(project.getCurrentStep());
        context.setTotalChapters(project.getTotalChapters());
        context.setChapterWordCount(project.getChapterWordCount());
        context.setChapterWordCountMin(project.getChapterWordCountMin());
        context.setChapterWordCountMax(project.getChapterWordCountMax());

        // Load world setting (prefer summary for chapter writing context)
        worldSettingRepository.findByProjectId(projectId)
                .ifPresent(ws -> {
                    if (chapterNumber > 0 && ws.getSummary() != null && !ws.getSummary().isBlank()) {
                        context.setWorldSetting(ws.getSummary());
                    } else {
                        context.setWorldSetting(ws.getContent());
                    }
                });

        // Load characters - overview (sort_order=0) goes to characters field
        List<CharacterEntity> chars = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        if (!chars.isEmpty()) {
            chars.stream().filter(c -> c.getSortOrder() == 0).findFirst()
                    .ifPresent(s -> {
                        if (chapterNumber > 0 && s.getSummary() != null && !s.getSummary().isBlank()) {
                            context.setCharacters(s.getSummary());
                        } else {
                            context.setCharacters(s.getContent());
                        }
                    });
            // Fallback: if no sort_order=0 entry, concat all (backward compat)
            if (context.getCharacters() == null) {
                StringBuilder sb = new StringBuilder();
                for (CharacterEntity c : chars) {
                    if (c.getContent() != null) sb.append(c.getContent()).append("\n\n");
                }
                context.setCharacters(sb.toString().trim());
            }
        }

        // Load outline
        storyOutlineRepository.findByProjectId(projectId).ifPresent(o -> {
            String fullContent = o.getContent();
            context.setOutline(fullContent);
            context.setTotalChapters(o.getTotalChapters());
            context.setStorySummary(fullContent);

            // For overallOutline: use storySummary (truncated) for chapter writing prompt
            if (fullContent != null) {
                context.setOverallOutline(truncate(fullContent, 800));
            }
        });

        // Load chapter info
        if (chapterNumber > 0) {
            context.setCurrentChapter(chapterNumber);
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .ifPresent(co -> {
                        context.setChapterTitle(co.getTitle());
                        context.setChapterSummary(co.getSummary());

                        // Load character cards for this chapter's characters
                        if (co.getCharacterNames() != null && !co.getCharacterNames().isBlank()) {
                            List<String> names = List.of(co.getCharacterNames().split("[,，、]"));
                            List<CharacterEntity> cards = characterRepository
                                    .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
                            StringBuilder cardSb = new StringBuilder();
                            for (CharacterEntity card : cards) {
                                boolean matched = names.stream()
                                        .anyMatch(n -> n.trim().equals(card.getName()));
                                if (matched && card.getContent() != null) {
                                    // Prefer summary over hard-truncated content
                                    String cardText;
                                    if (card.getSummary() != null && !card.getSummary().isBlank()) {
                                        cardText = card.getSummary();
                                    } else {
                                        cardText = card.getContent().length() > 600
                                                ? card.getContent().substring(0, 600) + "..."
                                                : card.getContent();
                                    }
                                    cardSb.append(cardText).append("\n\n");
                                }
                            }
                            if (!cardSb.isEmpty()) {
                                context.setCharacterCards(cardSb.toString().trim());
                            }
                        }
                    });

            // Next chapter outline (for continuity hints)
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber + 1)
                    .ifPresent(next -> {
                        context.setNextChapterTitle(next.getTitle());
                        context.setNextChapterSummary(next.getSummary());
                    });

            // Previous chapter context (last 300 chars of previous chapter)
            if (chapterNumber > 1) {
                chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                        .ifPresent(prev -> {
                            String content = prev.getContent();
                            if (content != null && content.length() > 300) {
                                content = "..." + content.substring(content.length() - 300);
                            }
                            context.setPreviousChapterContent(content);
                            if (prev.getCharacterStates() != null && !prev.getCharacterStates().isBlank()) {
                                context.setPreviousCharacterStates(prev.getCharacterStates());
                            }
                        });
            }
        }

        // Load step guidance for the current step
        stepGuidanceRepository.findByProjectIdAndStep(projectId, project.getCurrentStep())
                .ifPresent(sg -> context.setStepGuidance(sg.getGuidance()));

        return context;
    }

    public Flux<String> generate(Long projectId, WorkflowStep step) {
        return generate(projectId, step, 0);
    }

    public Flux<String> generate(Long projectId, WorkflowStep step, int chapterNumber) {
        // Check project status — disallow generation for completed/abandoned projects
        ProjectEntity proj = projectRepository.findById(projectId).orElse(null);
        if (proj != null && proj.getStatus() != null) {
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.COMPLETED) {
                return Flux.error(new IllegalStateException("项目状态为「已完本」，无法进行AI生成"));
            }
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.ABANDONED) {
                return Flux.error(new IllegalStateException("项目状态为「已废弃」，无法进行AI生成"));
            }
        }

        WorkflowStepHandler handler = handlers.get(step);
        if (handler == null) {
            return Flux.error(new IllegalArgumentException("No handler for step: " + step));
        }

        log.info("[P{}] Generate START step={} chapter={}", projectId, step, chapterNumber);
        long startTime = System.currentTimeMillis();

        // Mark as generating
        updateStepStatus(projectId, step, StepStatus.GENERATING);

        // Special handling for proofreading: multi-chapter multi-substep
        if (step == WorkflowStep.PROOFREADING) {
            return runProofreading(projectId)
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} elapsed={}s",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} elapsed={}s error={}",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        // Special handling for outline: generate per-chapter
        if (step == WorkflowStep.OUTLINE_GENERATION) {
            return generateOutlineByChapters(projectId)
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} elapsed={}s",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} elapsed={}s error={}",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        // Special handling for character design: generate overview + individual cards
        if (step == WorkflowStep.CHARACTER_DESIGN) {
            return generateCharactersByCards(projectId)
                    .doOnComplete(() -> log.info("[P{}] Generate DONE step={} elapsed={}s",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000))
                    .doOnError(e -> log.error("[P{}] Generate FAILED step={} elapsed={}s error={}",
                            projectId, step, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
        }

        WorkflowContext context = buildContext(projectId, chapterNumber);
        context.setCurrentStep(step);

        // Override guidance for the specific step being generated
        stepGuidanceRepository.findByProjectIdAndStep(projectId, step)
                .ifPresent(sg -> context.setStepGuidance(sg.getGuidance()));

        // For polishing, load the chapter content and polish note
        if (step == WorkflowStep.POLISHING && chapterNumber > 0) {
            chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .ifPresent(ch -> {
                        context.setContentToPolish(ch.getContent());
                        // Save current content as draft for comparison
                        ch.setContentDraft(ch.getContent());
                        chapterRepository.save(ch);
                        if (ch.getPolishNote() != null && !ch.getPolishNote().isBlank()) {
                            context.setPolishNote("【修改意见】\n" + ch.getPolishNote());
                        }
                    });
        }

        AiRequest request = handler.buildRequest(context);

        // Resolve model: step → project → global
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, step);
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request)
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[P{}] Generate DONE step={} chapter={} elapsed={}s",
                            projectId, step, chapterNumber, elapsed / 1000);
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), elapsed);
                })
                .doOnError(e -> log.error("[P{}] Generate FAILED step={} chapter={} elapsed={}s error={}",
                        projectId, step, chapterNumber, (System.currentTimeMillis() - startTime) / 1000, e.getMessage()));
    }

    private Flux<String> generateCharactersByCards(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int characterCount = project.getCharacterCount();
        WorkflowContext baseContext = buildContext(projectId, 0);
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);

        // Load guidance for character design
        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.CHARACTER_DESIGN)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        // Load existing cards for resume support
        List<CharacterEntity> existingCards = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        Set<Integer> completedCardNums = existingCards.stream()
                .filter(c -> c.getContent() != null && !c.getContent().isBlank())
                .map(CharacterEntity::getSortOrder)
                .collect(Collectors.toSet());

        // Pre-fill previousSummaries from existing cards (in order)
        List<String> previousSummaries = new ArrayList<>();
        for (int i = 1; i <= characterCount; i++) {
            int cardNum = i;
            existingCards.stream()
                .filter(c -> c.getSortOrder() == cardNum)
                .findFirst()
                .map(c -> extractCharacterSummary(c.getContent()))
                .ifPresent(previousSummaries::add);
        }

        log.info("[P{}] Character design: count={} completed={}",
                projectId, characterCount, completedCardNums.size());

        // Generate each character card sequentially, skipping already completed ones
        Flux<String> cardsFlux = Flux.range(1, characterCount)
                .concatMap(cardNum -> {
                    // Emit marker for frontend to identify new card start
                    Flux<String> marker = Flux.just("[[CHAR:CARD:" + cardNum + "]]");
                    Flux<String> header = cardNum > 1
                        ? Flux.just("\n\n---\n\n### 角色" + cardNum + "\n\n")
                        : Flux.just("### 角色" + cardNum + "\n\n");

                    if (completedCardNums.contains(cardNum)) {
                        // Already exists — skip generation, emit existing content
                        log.debug("[P{}] Character {} skipped (already exists)", projectId, cardNum);
                        String existing = existingCards.stream()
                            .filter(c -> c.getSortOrder() == cardNum)
                            .map(CharacterEntity::getContent).findFirst().orElse("");
                        return marker.concatWith(header).concatWith(Flux.just(existing));
                    }

                    // Generate new card
                    log.info("[P{}] Character {} generating", projectId, cardNum);
                    long cardStart = System.currentTimeMillis();
                    List<String> currentSummaries = new ArrayList<>(previousSummaries);

                    StringBuilder cardContent = new StringBuilder();
                    Flux<String> cardFlux = generateSingleCharacterCard(
                            baseContext, cardNum, characterCount, resolved, guidanceSuffix, currentSummaries)
                        .doOnNext(cardContent::append)
                        .doOnComplete(() -> {
                            String text = cardContent.toString();
                            saveSingleCharacter(projectId, cardNum, text);
                            previousSummaries.add(extractCharacterSummary(text));
                            long cardElapsed = System.currentTimeMillis() - cardStart;
                            log.info("[P{}] Character {} done ({}s)", projectId, cardNum, cardElapsed / 1000);
                            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), cardElapsed);
                        });

                    return marker.concatWith(header).concatWith(cardFlux);
                });

        // After all cards, generate a summary overview (always regenerate)
        Flux<String> summaryFlux = Flux.defer(() -> {
            // Delete existing overview only
            characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .ifPresent(c -> characterRepository.delete(c));

            Flux<String> marker = Flux.just("[[CHAR:OVERVIEW]]");
            Flux<String> header = Flux.just("\n\n---\n\n## 角色总览\n\n");
            StringBuilder overviewContent = new StringBuilder();
            long overviewStart = System.currentTimeMillis();
            String summaryPrompt = buildSummaryPrompt(baseContext, previousSummaries);
            String overviewSysPrompt = getSystemPromptForStep(WorkflowStep.CHARACTER_DESIGN);
            if (overviewSysPrompt == null) {
                overviewSysPrompt = "你是一位网络小说角色设计师，请根据已设计的角色信息生成简洁的角色总览概要。";
            }
            AiRequest req = AiRequest.builder()
                    .systemPrompt(overviewSysPrompt)
                    .userPrompt(summaryPrompt)
                    .maxTokens(1024)
                    .temperature(0.7)
                    .build();
            applyResolvedConfig(req, resolved);
            Flux<String> overviewFlux = resolved.provider().streamText(req)
                    .doOnNext(overviewContent::append)
                    .doOnComplete(() -> {
                        saveCharacterOverview(projectId, overviewContent.toString());
                        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - overviewStart);
                    });
            return marker.concatWith(header).concatWith(overviewFlux);
        });

        return cardsFlux.concatWith(summaryFlux);
    }

    private void saveSingleCharacter(Long projectId, int sortOrder, String content) {
        CharacterEntity card = new CharacterEntity();
        card.setProjectId(projectId);
        card.setSortOrder(sortOrder);
        card.setContent(content);
        card.setStatus("GENERATED");

        // Parse structured fields
        card.setName(truncateNullable(extractField(content, "姓名"), 100));
        if (card.getName() == null || card.getName().isBlank()) card.setName("角色" + sortOrder);
        card.setGender(truncateNullable(extractField(content, "性别"), 20));
        card.setAge(truncateNullable(extractField(content, "年龄"), 20));
        card.setRole(truncateNullable(extractField(content, "身份"), 50));
        card.setPersonality(truncateNullable(extractField(content, "性格"), 500));
        card.setAppearance(truncateNullable(extractField(content, "外貌"), 500));
        card.setBackground(extractField(content, "背景"));
        card.setMotivation(truncateNullable(extractField(content, "动机"), 500));
        card.setAbilities(truncateNullable(extractField(content, "能力"), 500));
        card.setRelationships(truncateNullable(extractField(content, "关系"), 500));

        // Generate compressed summary for chapter writing context
        String summary = contextSummaryService.summarizeCharacterCard(projectId, content);
        if (summary != null) {
            card.setSummary(summary);
        }

        characterRepository.save(card);
        log.info("Saved character card {} for project {}: {}", sortOrder, projectId, card.getName());
    }

    private void saveCharacterOverview(Long projectId, String content) {
        CharacterEntity overview = new CharacterEntity();
        overview.setProjectId(projectId);
        overview.setName("全部角色");
        overview.setSortOrder(0);
        overview.setContent(content);

        String summary = contextSummaryService.summarizeCharacterOverview(projectId, content);
        if (summary != null) {
            overview.setSummary(summary);
        }

        characterRepository.save(overview);
        log.info("Saved character overview for project {}", projectId);
    }

    private Flux<String> generateSingleCharacterCard(WorkflowContext baseContext,
                                                      int cardNum, int totalCards,
                                                      AiProviderRouter.ResolvedModel resolved,
                                                      String guidanceSuffix,
                                                      List<String> previousCharacterSummaries) {
        StringBuilder previousContext = new StringBuilder();
        if (!previousCharacterSummaries.isEmpty()) {
            previousContext.append("\n\n【已设计角色】\n");
            for (int i = 0; i < previousCharacterSummaries.size(); i++) {
                previousContext.append(i + 1).append(". ").append(previousCharacterSummaries.get(i)).append("\n");
            }
            previousContext.append("\n请设计一个与以上角色互补、有明确关系的新角色。\n");
        }

        String prompt = String.format("""
                你是一位网络小说角色设计师。请为以下小说设计第%d个主要角色（共%d个）的详细信息卡。

                【小说信息】标题：%s，题材：%s
                简介：%s
                【世界观摘要】%s
                %s
                请严格按以下纯文本格式输出角色信息卡，每个字段独占一行，【字段名】后直接写内容：

                【姓名】角色全名
                【性别】男/女/其他
                【年龄】具体数字或描述性年龄
                【身份】角色在故事中的身份/职业
                【性格】2-3个关键词加简短描述
                【外貌】简洁的外貌特征描述
                【背景】角色的前史和来历
                【动机】角色在故事中的核心驱动力
                【能力】角色的特长或能力
                【关系】与其他角色的关键关系

                注意：
                1. 这是第%d个角色（共%d个），请确保与其他角色有差异化设计，角色之间有张力。
                2. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
                3. 每个【字段】必须另起一行，不要将多个字段写在同一行。
                4. 用中文输出。
                """,
                cardNum, totalCards,
                baseContext.getTitle(),
                baseContext.getGenre() != null ? baseContext.getGenre().getDisplayName() : "",
                baseContext.getDescription() != null ? baseContext.getDescription() : "",
                wrapContent(truncate(baseContext.getWorldSetting(), 400)),
                previousContext.toString(),
                cardNum, totalCards) + guidanceSuffix;

        String systemPrompt = getSystemPromptForStep(WorkflowStep.CHARACTER_DESIGN);
        if (systemPrompt == null) {
            systemPrompt = "你是一位网络小说角色设计师，请生成详细的角色信息卡。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(2048)
                .temperature(0.8)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }

    private String extractCharacterSummary(String cardContent) {
        String name = extractField(cardContent, "姓名");
        String role = extractField(cardContent, "身份");
        String personality = extractField(cardContent, "性格");
        return (name != null ? name : "?") + " - "
             + (role != null ? role : "?") + " - "
             + (personality != null ? personality : "?");
    }

    private String buildSummaryPrompt(WorkflowContext ctx, List<String> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                请根据以下小说信息和已设计的角色，生成一份简洁的角色总览概要（300-400字）。

                【小说信息】
                标题：%s
                题材：%s
                简介：%s

                【已设计角色】
                """,
                ctx.getTitle(),
                ctx.getGenre() != null ? ctx.getGenre().getDisplayName() : "",
                ctx.getDescription() != null ? ctx.getDescription() : ""));

        for (int i = 0; i < summaries.size(); i++) {
            sb.append(i + 1).append(". ").append(summaries.get(i)).append("\n");
        }

        sb.append("""

                请输出角色总览，概括每个角色的核心定位、彼此之间的关系网络，以及他们在故事中的作用。
                用中文输出，简洁扼要。
                """);
        return sb.toString();
    }

    // --- Volume-based outline generation ---

    private record VolumeRange(int volumeNumber, int chapterStart, int chapterEnd) {}

    private List<VolumeRange> computeVolumes(int totalChapters) {
        int volumeSize = 10;
        List<VolumeRange> volumes = new ArrayList<>();
        int vol = 1;
        for (int start = 1; start <= totalChapters; start += volumeSize) {
            int end = Math.min(start + volumeSize - 1, totalChapters);
            volumes.add(new VolumeRange(vol++, start, end));
        }
        return volumes;
    }

    private Flux<String> generateOutlineByChapters(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();

        WorkflowContext baseContext = buildContext(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.OUTLINE_GENERATION);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        List<VolumeRange> volumes = computeVolumes(totalChapters);

        // Phase 0: Pre-create all chapter outline records as PENDING
        preCreateChapterOutlines(projectId, totalChapters, volumes);

        // Load existing data for resume support
        List<VolumeOutlineEntity> existingVolumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        List<ChapterOutlineEntity> existingChapters = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);

        Set<Integer> completedVolumeNums = existingVolumes.stream()
                .filter(v -> v.getArcSummary() != null && !v.getArcSummary().isBlank())
                .map(VolumeOutlineEntity::getVolumeNumber)
                .collect(Collectors.toSet());

        Set<Integer> completedChapterNums = existingChapters.stream()
                .filter(ch -> "COMPLETED".equals(ch.getStatus()))
                .map(ChapterOutlineEntity::getChapterNumber)
                .collect(Collectors.toSet());

        log.info("[P{}] Outline generation: totalChapters={} volumes={} completedVolumes={} completedChapters={}",
                projectId, totalChapters, volumes.size(), completedVolumeNums.size(), completedChapterNums.size());

        // Pre-fill volumeArcSummaries from existing data
        List<String> volumeArcSummaries = new ArrayList<>();
        for (VolumeRange vol : volumes) {
            existingVolumes.stream()
                    .filter(v -> v.getVolumeNumber() == vol.volumeNumber())
                    .findFirst()
                    .map(VolumeOutlineEntity::getArcSummary)
                    .filter(s -> s != null && !s.isBlank())
                    .ifPresentOrElse(volumeArcSummaries::add, () -> volumeArcSummaries.add(""));
        }

        // Phase 1: Generate volume arcs — skip already completed
        Flux<String> phase1 = Flux.fromIterable(volumes)
                .concatMap(vol -> {
                    String marker = "[[SECTION:VOLUME:" + vol.volumeNumber() + ":" + vol.chapterStart() + ":" + vol.chapterEnd() + "]]";
                    if (completedVolumeNums.contains(vol.volumeNumber())) {
                        log.debug("[P{}] Volume {} skipped (already exists)", projectId, vol.volumeNumber());
                        String existing = volumeArcSummaries.get(vol.volumeNumber() - 1);
                        return Flux.just(marker, existing);
                    }
                    // Need to generate
                    log.info("[P{}] Volume {} generating (ch{}-{})", projectId, vol.volumeNumber(), vol.chapterStart(), vol.chapterEnd());
                    long volStart = System.currentTimeMillis();
                    StringBuilder arcContent = new StringBuilder();
                    Flux<String> arcFlux = generateSingleVolumeArc(baseContext, vol, totalChapters, resolved, guidanceSuffix, volumeArcSummaries)
                            .doOnNext(arcContent::append)
                            .doOnComplete(() -> {
                                String text = arcContent.toString();
                                volumeArcSummaries.set(vol.volumeNumber() - 1, text);
                                saveSingleVolumeArc(projectId, vol, text);
                                long volElapsed = System.currentTimeMillis() - volStart;
                                log.info("[P{}] Volume {} done ({}s, {}chars)", projectId, vol.volumeNumber(),
                                        volElapsed / 1000, text.length());
                                aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), volElapsed);
                            });
                    return Flux.just(marker).concatWith(arcFlux);
                });

        // Phase 2: Generate chapter outlines — skip already completed
        Flux<String> phase2 = Flux.fromIterable(volumes)
                .concatMap(vol -> {
                    AtomicReference<String> prevChapterOutline = new AtomicReference<>("");
                    // Initialize prevChapterOutline from the last chapter before this volume's range
                    if (vol.chapterStart() > 1) {
                        existingChapters.stream()
                                .filter(ch -> ch.getChapterNumber() == vol.chapterStart() - 1)
                                .findFirst()
                                .map(ChapterOutlineEntity::getSummary)
                                .ifPresent(prevChapterOutline::set);
                    }

                    return Flux.range(vol.chapterStart(), vol.chapterEnd() - vol.chapterStart() + 1)
                            .concatMap(chapterNum -> {
                                String chMarker = "[[SECTION:CHAPTER:" + chapterNum + ":" + vol.volumeNumber() + "]]";
                                if (completedChapterNums.contains(chapterNum)) {
                                    log.debug("[P{}] Chapter outline {} skipped (already exists)", projectId, chapterNum);
                                    String existingSummary = existingChapters.stream()
                                            .filter(ch -> ch.getChapterNumber() == chapterNum)
                                            .findFirst()
                                            .map(ChapterOutlineEntity::getSummary)
                                            .orElse("");
                                    prevChapterOutline.set(existingSummary);
                                    return Flux.just(chMarker, existingSummary);
                                }
                                // Mark as GENERATING
                                updateChapterOutlineStatus(projectId, chapterNum, "GENERATING");
                                log.info("[P{}] Chapter outline {} generating (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long chStart = System.currentTimeMillis();
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);
                                String prevOutline = prevChapterOutline.get();
                                StringBuilder chContent = new StringBuilder();
                                Flux<String> chFlux = generateSingleChapterOutlineV2(
                                        baseContext, chapterNum, totalChapters, vol, volumeArc, prevOutline, "", resolved, guidanceSuffix)
                                        .doOnNext(chContent::append)
                                        .doOnComplete(() -> {
                                            String text = chContent.toString();
                                            prevChapterOutline.set(text);
                                            saveSingleChapterOutline(projectId, chapterNum, vol.volumeNumber(), text);
                                            updateChapterOutlineStatus(projectId, chapterNum, "COMPLETED");
                                            long chElapsed = System.currentTimeMillis() - chStart;
                                            log.info("[P{}] Chapter outline {} done ({}s, {}chars)", projectId, chapterNum,
                                                    chElapsed / 1000, text.length());
                                            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), chElapsed);
                                        })
                                        .doOnError(e -> updateChapterOutlineStatus(projectId, chapterNum, "FAILED"));
                                return Flux.just(chMarker).concatWith(chFlux);
                            });
                });

        // Phase 2.5: Refine chapter outlines for cross-chapter coherence
        Flux<String> phase25 = Flux.defer(() -> {
            // Reload all chapter outlines to get latest data
            List<ChapterOutlineEntity> allOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
            Set<Integer> alreadyRefined = allOutlines.stream()
                    .filter(ChapterOutlineEntity::isRefined)
                    .map(ChapterOutlineEntity::getChapterNumber)
                    .collect(Collectors.toSet());

            log.info("[P{}] Phase 2.5 refine: total={} alreadyRefined={}", projectId, allOutlines.size(), alreadyRefined.size());

            return Flux.fromIterable(volumes)
                    .concatMap(vol -> Flux.range(vol.chapterStart(), vol.chapterEnd() - vol.chapterStart() + 1)
                            .concatMap(chapterNum -> {
                                if (alreadyRefined.contains(chapterNum)) {
                                    log.debug("[P{}] Chapter {} refine skipped (already refined)", projectId, chapterNum);
                                    return Flux.empty();
                                }

                                String refineMarker = "[[SECTION:REFINE:" + chapterNum + ":" + vol.volumeNumber() + "]]";

                                // Load prev/current/next from DB (prev may have been refined already)
                                String prevOutline = chapterNum > 1
                                        ? chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum - 1)
                                            .map(ChapterOutlineEntity::getSummary).orElse("")
                                        : "";
                                String currentOutline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                                        .map(ChapterOutlineEntity::getSummary).orElse("");
                                String nextOutline = chapterNum < totalChapters
                                        ? chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum + 1)
                                            .map(ChapterOutlineEntity::getSummary).orElse("")
                                        : "";
                                String volumeArc = volumeArcSummaries.get(vol.volumeNumber() - 1);

                                updateChapterOutlineStatus(projectId, chapterNum, "REFINING");
                                log.info("[P{}] Chapter {} refining (vol{})", projectId, chapterNum, vol.volumeNumber());
                                long refineStart = System.currentTimeMillis();
                                StringBuilder refineContent = new StringBuilder();

                                Flux<String> refineFlux = generateSingleChapterRefine(
                                        baseContext, chapterNum, totalChapters, volumeArc,
                                        prevOutline, currentOutline, nextOutline, resolved, guidanceSuffix)
                                        .doOnNext(refineContent::append)
                                        .doOnComplete(() -> {
                                            String text = refineContent.toString();
                                            saveSingleChapterOutline(projectId, chapterNum, vol.volumeNumber(), text);
                                            // Mark as refined
                                            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                                                    .ifPresent(entity -> {
                                                        entity.setRefined(true);
                                                        entity.setStatus("REFINED");
                                                        chapterOutlineRepository.save(entity);
                                                    });
                                            long refineElapsed = System.currentTimeMillis() - refineStart;
                                            log.info("[P{}] Chapter {} refined ({}s, {}chars)", projectId, chapterNum,
                                                    refineElapsed / 1000, text.length());
                                            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), refineElapsed);
                                        })
                                        .doOnError(e -> updateChapterOutlineStatus(projectId, chapterNum, "FAILED"));

                                return Flux.just(refineMarker).concatWith(refineFlux);
                            }));
        });

        // Phase 3: Generate story summary — always regenerate
        Flux<String> phase3 = Flux.defer(() -> {
            String summaryMarker = "[[SECTION:SUMMARY]]";
            long summaryStart = System.currentTimeMillis();
            StringBuilder summaryContent = new StringBuilder();
            Flux<String> summaryFlux = generateStorySummary(baseContext, totalChapters, volumeArcSummaries, resolved, guidanceSuffix)
                    .doOnNext(summaryContent::append)
                    .doOnComplete(() -> {
                        saveStorySummaryToDb(projectId, summaryContent.toString());
                        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - summaryStart);
                    });
            return Flux.just(summaryMarker).concatWith(summaryFlux);
        });

        return phase1.concatWith(phase2).concatWith(phase25).concatWith(phase3);
    }

    /**
     * Regenerate a single chapter outline (for use by the "regenerate" button).
     * Uses the same logic as generateSingleChapterOutlineV2 but standalone.
     */
    public Flux<String> regenerateChapterOutline(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int totalChapters = project.getTotalChapters();
        WorkflowContext baseContext = buildContext(projectId, 0);
        baseContext.setTotalChapters(totalChapters);

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.OUTLINE_GENERATION);
        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.OUTLINE_GENERATION)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        // Determine volume for this chapter
        List<VolumeRange> volumes = computeVolumes(totalChapters);
        VolumeRange vol = volumes.stream()
                .filter(v -> chapterNumber >= v.chapterStart() && chapterNumber <= v.chapterEnd())
                .findFirst()
                .orElse(new VolumeRange(1, 1, totalChapters));

        // Load volume arc
        String volumeArc = volumeOutlineRepository.findByProjectIdAndVolumeNumber(projectId, vol.volumeNumber())
                .map(VolumeOutlineEntity::getArcSummary)
                .orElse("");

        // Load previous chapter outline
        String prevOutline = "";
        if (chapterNumber > 1) {
            prevOutline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                    .map(ChapterOutlineEntity::getSummary)
                    .orElse("");
        }

        // Load next chapter outline for continuity
        String nextOutline = "";
        if (chapterNumber < totalChapters) {
            nextOutline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber + 1)
                    .map(ChapterOutlineEntity::getSummary)
                    .orElse("");
        }

        log.info("[P{}] Regenerating chapter outline {}", projectId, chapterNumber);
        long regenStart = System.currentTimeMillis();

        StringBuilder chContent = new StringBuilder();
        return generateSingleChapterOutlineV2(baseContext, chapterNumber, totalChapters, vol, volumeArc, prevOutline, nextOutline, resolved, guidanceSuffix)
                .doOnNext(chContent::append)
                .doOnComplete(() -> {
                    String text = chContent.toString();
                    saveSingleChapterOutline(projectId, chapterNumber, vol.volumeNumber(), text);
                    long regenElapsed = System.currentTimeMillis() - regenStart;
                    log.info("[P{}] Chapter outline {} regenerated ({}chars, {}s)", projectId, chapterNumber, text.length(), regenElapsed / 1000);
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), regenElapsed);
                });
    }

    private Flux<String> generateSingleVolumeArc(WorkflowContext baseContext,
                                                   VolumeRange vol, int totalChapters,
                                                   AiProviderRouter.ResolvedModel resolved,
                                                   String guidanceSuffix,
                                                   List<String> previousArcSummaries) {
        StringBuilder previousContext = new StringBuilder();
        if (!previousArcSummaries.isEmpty()) {
            previousContext.append("\n【前文各卷弧线摘要】\n");
            for (int i = 0; i < previousArcSummaries.size(); i++) {
                previousContext.append("第").append(i + 1).append("卷：")
                        .append(truncate(previousArcSummaries.get(i), 300)).append("\n\n");
            }
        }

        String prompt = String.format("""
                你是一位经验丰富的网络小说策划。请为以下小说生成第%d卷（第%d-%d章，全书共%d章）的故事弧线。

                【小说信息】
                标题：%s
                题材：%s
                简介：%s

                【世界观】
                %s

                【主要角色】
                %s
                %s
                请生成本卷的故事弧线，包括：
                1. 本卷主冲突/核心事件
                2. 情绪弧线（起承转合）
                3. 关键转折点（1-2个）
                4. 与前后卷的衔接（如有前卷，承接前文；暗示后续发展）

                简洁概括，300-500字。用中文输出。请使用纯文本格式，不要使用Markdown标记（如星号、井号等）。
                """,
                vol.volumeNumber(), vol.chapterStart(), vol.chapterEnd(), totalChapters,
                baseContext.getTitle(),
                baseContext.getGenre() != null ? baseContext.getGenre().getDisplayName() : "",
                baseContext.getDescription() != null ? baseContext.getDescription() : "",
                wrapContent(truncate(baseContext.getWorldSetting(), 400)),
                wrapContent(truncate(baseContext.getCharacters(), 400)),
                previousContext.toString()) + guidanceSuffix;

        String systemPrompt = getSystemPromptForStep(WorkflowStep.OUTLINE_GENERATION);
        if (systemPrompt == null) {
            systemPrompt = "你是一位经验丰富的网络小说策划，擅长设计故事弧线和节奏控制。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1024)
                .temperature(0.75)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("VolumeArc-" + vol.volumeNumber()));
    }

    private Flux<String> generateSingleChapterOutlineV2(WorkflowContext baseContext,
                                                          int chapterNum, int totalChapters,
                                                          VolumeRange vol, String volumeArc,
                                                          String previousChapterOutline,
                                                          String nextChapterOutline,
                                                          AiProviderRouter.ResolvedModel resolved,
                                                          String guidanceSuffix) {
        String phaseHint;
        double progress = (double) chapterNum / totalChapters;
        if (progress <= 0.2) phaseHint = "开篇引入阶段";
        else if (progress <= 0.4) phaseHint = "发展铺垫阶段";
        else if (progress <= 0.6) phaseHint = "中段高潮阶段";
        else if (progress <= 0.8) phaseHint = "深入发展阶段";
        else phaseHint = "收束结局阶段";

        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(truncate(volumeArc, 500)));
        }
        if (previousChapterOutline != null && !previousChapterOutline.isBlank()) {
            contextInfo.append("\n【前一章大纲】").append(wrapContent(truncate(previousChapterOutline, 300)));
        }
        if (nextChapterOutline != null && !nextChapterOutline.isBlank()) {
            contextInfo.append("\n【后一章大纲】").append(wrapContent(truncate(nextChapterOutline, 300)));
        }

        String prompt = String.format("""
                你是一位网络小说策划。请为以下小说生成第%d章（共%d章，本卷第%d-%d章）的详细大纲。

                【小说信息】标题：%s，题材：%s
                【世界观摘要】%s
                【主要角色】%s
                【当前阶段】%s（第%d/%d章）
                %s
                请严格按以下格式输出本章大纲，不要改变格式，不要输出任何分析、解释、策划笔记、前言或总结：

                **标题：**（4-10字的章节标题）

                **出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

                （用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。注意与前后章节的连贯性和本卷弧线的一致性。）

                注意：直接输出上述格式内容即可，不要添加任何其他文字。
                """,
                chapterNum, totalChapters, vol.chapterStart(), vol.chapterEnd(),
                baseContext.getTitle(),
                baseContext.getGenre() != null ? baseContext.getGenre().getDisplayName() : "",
                wrapContent(truncate(baseContext.getWorldSetting(), 300)),
                wrapContent(truncate(baseContext.getCharacters(), 300)),
                phaseHint, chapterNum, totalChapters,
                contextInfo.toString()) + guidanceSuffix;

        String systemPrompt = getSystemPromptForStep(WorkflowStep.OUTLINE_GENERATION);
        if (systemPrompt == null) {
            systemPrompt = "你是一位网络小说策划，请简洁地生成单章大纲。直接输出大纲内容，禁止输出任何分析、评论或解释说明。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(768)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("ChapterOutline-" + chapterNum));
    }

    private Flux<String> generateSingleChapterRefine(WorkflowContext baseContext,
                                                       int chapterNum, int totalChapters,
                                                       String volumeArc,
                                                       String prevChapterOutline,
                                                       String currentChapterOutline,
                                                       String nextChapterOutline,
                                                       AiProviderRouter.ResolvedModel resolved,
                                                       String guidanceSuffix) {
        StringBuilder contextInfo = new StringBuilder();
        if (volumeArc != null && !volumeArc.isBlank()) {
            contextInfo.append("\n【本卷故事弧线】").append(wrapContent(truncate(volumeArc, 500)));
        }
        if (prevChapterOutline != null && !prevChapterOutline.isBlank()) {
            contextInfo.append("\n【前一章大纲】").append(wrapContent(truncate(prevChapterOutline, 300)));
        }
        contextInfo.append("\n【本章当前大纲】").append(wrapContent(currentChapterOutline));
        if (nextChapterOutline != null && !nextChapterOutline.isBlank()) {
            contextInfo.append("\n【后一章大纲】").append(wrapContent(truncate(nextChapterOutline, 300)));
        }

        String prompt = String.format("""
                你正在对已生成的章节大纲进行精修，以消除跨章节的内容重叠和前后矛盾。

                【小说信息】标题：%s，题材：%s
                【世界观摘要】%s
                【主要角色】%s
                【当前位置】第%d章（共%d章）
                %s
                请精修本章大纲，要求：
                1. 保留核心事件和情感基调不变
                2. 消除与前后章节的内容重复或矛盾
                3. 确保章节间过渡自然、逻辑连贯
                4. 如发现本章内容与前后章高度重复，调整本章侧重点使其差异化

                请严格按以下格式直接输出精修后的大纲，不要改变格式。禁止输出任何分析过程、精修说明、策划笔记、修改理由或前言后语，只输出最终大纲本身：

                **标题：**（4-10字的章节标题，可沿用或微调原标题）

                **出场角色：**（从主要角色中选择本章涉及的角色姓名，逗号分隔）

                （用2-4句话描述本章核心事件、涉及角色、情绪基调和章末悬念，100-200字。）
                """,
                baseContext.getTitle(),
                baseContext.getGenre() != null ? baseContext.getGenre().getDisplayName() : "",
                wrapContent(truncate(baseContext.getWorldSetting(), 300)),
                wrapContent(truncate(baseContext.getCharacters(), 300)),
                chapterNum, totalChapters,
                contextInfo.toString()) + guidanceSuffix;

        String systemPrompt = getSystemPromptForStep(WorkflowStep.OUTLINE_GENERATION);
        if (systemPrompt == null) {
            systemPrompt = "你是一位网络小说策划，正在对章节大纲进行精修校对。直接输出精修后的大纲内容，禁止输出任何分析过程、修改说明或策划笔记。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(768)
                .temperature(0.65)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("ChapterRefine-" + chapterNum));
    }

    private Flux<String> generateStorySummary(WorkflowContext baseContext, int totalChapters,
                                               List<String> volumeArcSummaries,
                                               AiProviderRouter.ResolvedModel resolved,
                                               String guidanceSuffix) {
        StringBuilder arcsInfo = new StringBuilder();
        for (int i = 0; i < volumeArcSummaries.size(); i++) {
            arcsInfo.append("第").append(i + 1).append("卷：")
                    .append(wrapContent(truncate(volumeArcSummaries.get(i), 400))).append("\n");
        }

        String prompt = String.format("""
                你是一位经验丰富的网络小说策划。请根据以下小说信息和各卷弧线，生成一份完整的故事总纲/主线描述。

                【小说信息】
                标题：%s
                题材：%s
                简介：%s
                总章数：%d

                【各卷故事弧线】
                %s

                请生成一份500-800字的故事总纲，概括：
                1. 整体故事主线和核心冲突
                2. 主角的成长/变化轨迹
                3. 各阶段关键转折的串联
                4. 故事的最终走向和主题升华

                用中文输出，确保总纲能完整概括全书脉络。
                """,
                baseContext.getTitle(),
                baseContext.getGenre() != null ? baseContext.getGenre().getDisplayName() : "",
                baseContext.getDescription() != null ? baseContext.getDescription() : "",
                totalChapters,
                arcsInfo.toString()) + guidanceSuffix;

        String systemPrompt = getSystemPromptForStep(WorkflowStep.OUTLINE_GENERATION);
        if (systemPrompt == null) {
            systemPrompt = "你是一位经验丰富的网络小说策划，请生成完整的故事总纲。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1536)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return Flux.defer(() -> resolved.provider().streamText(request))
                .retryWhen(retryOnConnectionReset("StorySummary"));
    }

    // --- Retry helper for transient network errors ---

    private Retry retryOnConnectionReset(String context) {
        return Retry.backoff(3, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(this::isRetryableError)
                .doBeforeRetry(signal -> log.warn("[{}] Retrying due to transient error (attempt {}): {}",
                        context, signal.totalRetries() + 1, signal.failure().getMessage()));
    }

    private boolean isRetryableError(Throwable e) {
        if (e instanceof IOException) return true;
        String msg = e.getMessage();
        if (msg == null) {
            return e.getCause() instanceof IOException;
        }
        return msg.contains("Connection reset")
                || msg.contains("connection reset")
                || msg.contains("Connection refused")
                || msg.contains("Connection timed out")
                || msg.contains("Connection prematurely closed")
                || msg.contains("premature close")
                || msg.contains("GOAWAY")
                || msg.contains("connection was aborted");
    }

    // --- Incremental save methods for outline generation ---

    private void saveSingleVolumeArc(Long projectId, VolumeRange vol, String content) {
        VolumeOutlineEntity entity = volumeOutlineRepository
                .findByProjectIdAndVolumeNumber(projectId, vol.volumeNumber())
                .orElseGet(() -> {
                    VolumeOutlineEntity v = new VolumeOutlineEntity();
                    v.setProjectId(projectId);
                    v.setVolumeNumber(vol.volumeNumber());
                    return v;
                });
        entity.setChapterStart(vol.chapterStart());
        entity.setChapterEnd(vol.chapterEnd());
        entity.setArcSummary(content);
        entity.setTitle("第" + vol.volumeNumber() + "卷");
        volumeOutlineRepository.save(entity);
    }

    private void preCreateChapterOutlines(Long projectId, int totalChapters, List<VolumeRange> volumes) {
        for (VolumeRange vol : volumes) {
            for (int chNum = vol.chapterStart(); chNum <= vol.chapterEnd(); chNum++) {
                int chapterNum = chNum;
                ChapterOutlineEntity existing = chapterOutlineRepository
                        .findByProjectIdAndChapterNumber(projectId, chapterNum)
                        .orElse(null);
                if (existing == null) {
                    // Create placeholder
                    ChapterOutlineEntity entity = new ChapterOutlineEntity();
                    entity.setProjectId(projectId);
                    entity.setChapterNumber(chapterNum);
                    entity.setVolumeNumber(vol.volumeNumber());
                    entity.setStatus("PENDING");
                    chapterOutlineRepository.save(entity);
                } else if ("GENERATING".equals(existing.getStatus())) {
                    // Reset stuck GENERATING to PENDING
                    existing.setStatus("PENDING");
                    chapterOutlineRepository.save(existing);
                }
                // If COMPLETED, skip
            }
        }
        log.info("[P{}] Pre-created/verified {} chapter outline records", projectId, totalChapters);
    }

    private void updateChapterOutlineStatus(Long projectId, int chapterNum, String status) {
        chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                .ifPresent(entity -> {
                    entity.setStatus(status);
                    chapterOutlineRepository.save(entity);
                });
    }

    private void saveSingleChapterOutline(Long projectId, int chapterNum, int volumeNum, String content) {
        // Post-processing: strip AI commentary before/after the actual outline
        String text = content.strip();

        // Strip leading commentary before the actual outline start
        int titleIdx = text.indexOf("**标题：**");
        if (titleIdx < 0) titleIdx = text.indexOf("**标题:**");
        if (titleIdx > 0) {
            text = text.substring(titleIdx);
        }

        // Strip trailing commentary after the outline body
        String[] trailingPatterns = {"---", "策划笔记", "修改说明", "精修逻辑", "【备注】", "【说明】", "注：", "注意："};
        for (String pattern : trailingPatterns) {
            int idx = text.lastIndexOf(pattern);
            if (idx > 0) {
                int newlineIdx = text.lastIndexOf('\n', idx);
                if (newlineIdx >= 0 && text.substring(newlineIdx, idx).isBlank()) {
                    text = text.substring(0, newlineIdx).stripTrailing();
                }
            }
        }

        content = text;

        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        String title = null;
        Matcher tMatcher = titlePattern.matcher(content);
        if (tMatcher.find()) {
            title = tMatcher.group(1).trim();
            if (title.length() > 200) title = title.substring(0, 200);
        }

        String characterNames = null;
        Matcher cMatcher = characterPattern.matcher(content);
        if (cMatcher.find()) {
            characterNames = cMatcher.group(1).trim();
            if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
        }

        String summary = content
                .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                .strip();

        ChapterOutlineEntity entity = chapterOutlineRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNum)
                .orElseGet(() -> {
                    ChapterOutlineEntity e = new ChapterOutlineEntity();
                    e.setProjectId(projectId);
                    e.setChapterNumber(chapterNum);
                    return e;
                });
        entity.setVolumeNumber(volumeNum);
        entity.setTitle(title != null ? title : "第" + chapterNum + "章");
        entity.setSummary(summary);
        entity.setCharacterNames(characterNames);
        entity.setStatus("COMPLETED");
        entity.setRefined(false);
        chapterOutlineRepository.save(entity);
    }

    private void saveStorySummaryToDb(Long projectId, String content) {
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String wrapContent(String content) {
        if (content == null || content.isBlank()) return "";
        String sanitized = content.replace('`', '\uff40');
        return "\n```\n" + sanitized + "\n```\n";
    }

    private String getSystemPromptForStep(WorkflowStep step) {
        String sp = promptRegistry.getSystemPrompt(step, null);
        return sp != null && !sp.isBlank() ? sp : null;
    }

    private void applyResolvedConfig(AiRequest request, AiProviderRouter.ResolvedModel resolved) {
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());
    }

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content) {
        saveGeneratedContent(projectId, step, content, 0);
    }

    @Transactional
    public void saveGeneratedContent(Long projectId, WorkflowStep step, String content, int chapterNumber) {
        log.info("[P{}] saveGeneratedContent step={} chapter={} contentLen={}", projectId, step, chapterNumber,
                content != null ? content.length() : 0);
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    return s;
                });
        // For incrementally-saved steps, don't store the huge raw stream in workflow_state
        if (step == WorkflowStep.OUTLINE_GENERATION || step == WorkflowStep.CHARACTER_DESIGN) {
            state.setGeneratedContent("[data saved incrementally]");
        } else {
            state.setGeneratedContent(content);
        }
        state.setStatus(StepStatus.GENERATED);
        workflowStateRepository.save(state);

        // Also save to specific tables
        switch (step) {
            case WORLD_BUILDING -> saveWorldSetting(projectId, content);
            case CHARACTER_DESIGN -> {
                // Characters are saved per-card in generateCharactersByCards()
            }
            case OUTLINE_GENERATION -> {
                // Outline data is saved incrementally in generateOutlineByChapters()
            }
            case CHAPTER_WRITING -> saveChapter(projectId, chapterNumber, content);
            case POLISHING -> {
                if (chapterNumber > 0) {
                    saveChapter(projectId, chapterNumber, content);
                    // Mark polish status as completed
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                            .ifPresent(ch -> {
                                ch.setPolishStatus(StepStatus.CONFIRMED);
                                chapterRepository.save(ch);
                            });
                }
            }
            case PROOFREADING -> {
                // Proofreading results are saved in runProofreading() per-chapter
                // This case only updates the workflow_state above
            }
        }
    }

    public void generateCharacterStates(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).orElse(null);
        if (chapter == null || chapter.getContent() == null || chapter.getContent().isBlank()) return;

        AiProviderRouter.ResolvedModel resolved = resolveModelForProject(projectId);

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

        String userPrompt = "请根据以下章节内容，汇总本章结束时各出场角色的当前状态。\n"
                + "包括：修为等级、重要装备/宝物、所处位置、关键关系变化等。\n"
                + "每个角色一行，格式「角色名：状态描述」，每行不超过50字。只输出状态信息，不要其他内容。\n"
                + "请使用纯文本格式，不要使用Markdown标记。\n\n"
                + (charNames.isBlank() ? "" : "【本章出场角色】\n" + charNames + "\n\n")
                + (prevStates.isBlank() ? "" : "【前章角色状态（参考）】\n" + prevStates + "\n\n")
                + "【章节内容】\n" + excerpt;

        AiRequest request = AiRequest.builder()
                .systemPrompt("你是一位小说编辑助手，擅长从章节内容中提取角色状态变化。请简洁准确地汇总。")
                .userPrompt(userPrompt)
                .maxTokens(500)
                .temperature(0.3)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder result = new StringBuilder();
        long start = System.currentTimeMillis();
        resolved.provider().streamText(request)
                .doOnNext(result::append)
                .blockLast();
        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - start);

        String states = result.toString().trim();
        if (!states.isBlank()) {
            chapter.setCharacterStates(states);
            chapterRepository.save(chapter);
            log.info("[P{}] Generated character states for chapter {} ({}chars)", projectId, chapterNumber, states.length());
        }
    }

    @Transactional
    public void ensureWorkflowStateExists(Long projectId, WorkflowStep step) {
        workflowStateRepository.findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    s.setStatus(StepStatus.GENERATED);
                    s.setGeneratedContent("[auto-completed]");
                    return workflowStateRepository.save(s);
                });
    }

    /**
     * Confirm step content AND advance to next step.
     * Used by AutoRunService for automated flow.
     */
    @Transactional
    public void confirmStep(Long projectId, WorkflowStep step) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseThrow(() -> new IllegalStateException("Step not generated yet: " + step));
        state.setStatus(StepStatus.CONFIRMED);
        workflowStateRepository.save(state);

        // Advance project to next step
        WorkflowStep nextStep = step.next();
        if (nextStep != null) {
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
            project.setCurrentStep(nextStep);
            projectRepository.save(project);
        }
    }

    /**
     * Only confirm step content without advancing to next step.
     */
    @Transactional
    public void confirmStepOnly(Long projectId, WorkflowStep step) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseThrow(() -> new IllegalStateException("Step not generated yet: " + step));
        state.setStatus(StepStatus.CONFIRMED);
        workflowStateRepository.save(state);
    }

    /**
     * Advance to next step without confirming current step content.
     * Ensures a workflow_state row exists so the step can be skipped.
     */
    @Transactional
    public void advanceStep(Long projectId, WorkflowStep step) {
        ensureWorkflowStateExists(projectId, step);
        WorkflowStep nextStep = step.next();
        if (nextStep != null) {
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
            project.setCurrentStep(nextStep);
            projectRepository.save(project);
        }
    }

    @Transactional
    public void saveUserEdit(Long projectId, WorkflowStep step, String editedContent) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    s.setStatus(StepStatus.GENERATED);
                    return s;
                });
        state.setUserEditedContent(editedContent);
        workflowStateRepository.save(state);

        // Update specific tables with edited content
        switch (step) {
            case WORLD_BUILDING -> saveWorldSetting(projectId, editedContent);
            case CHARACTER_DESIGN -> saveCharacterOverviewOnly(projectId, editedContent);
            case OUTLINE_GENERATION -> saveOutline(projectId, editedContent);
        }
    }

    /**
     * Save only the overview portion from the edited content, without touching individual character cards.
     */
    private void saveCharacterOverviewOnly(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        // Extract overview content from the full text
        String overviewContent = null;
        String[] segments = content.split("\\n+\\s*-{3,}\\s*\\n+");
        for (String segment : segments) {
            if (segment.contains("## 角色总览")) {
                overviewContent = segment.replaceFirst("(?s).*?## 角色总览\\s*", "").strip();
                break;
            }
        }

        if (overviewContent == null) {
            // No overview section found — treat entire content as overview
            overviewContent = content.strip();
        }

        // Update or create the overview entity (sortOrder=0) only
        List<CharacterEntity> all = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        CharacterEntity overview = all.stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .orElseGet(() -> {
                    CharacterEntity o = new CharacterEntity();
                    o.setProjectId(projectId);
                    o.setName("全部角色");
                    o.setSortOrder(0);
                    return o;
                });
        overview.setContent(overviewContent);
        String overviewSummary = contextSummaryService.summarizeCharacterOverview(projectId, overviewContent);
        if (overviewSummary != null) overview.setSummary(overviewSummary);
        characterRepository.save(overview);
    }

    @Transactional
    public void resetGeneratingStatus(Long projectId, WorkflowStep step, int chapterNumber) {
        // Skip reset if background task is actively running
        if (backgroundGenerationService != null && backgroundGenerationService.isActive(projectId, step, chapterNumber)) {
            log.info("[P{}] Skipping resetGeneratingStatus - bg task active step={}", projectId, step);
            return;
        }
        // Reset workflow state
        workflowStateRepository.findByProjectIdAndStep(projectId, step).ifPresent(state -> {
            if (state.getStatus() == StepStatus.GENERATING) {
                state.setStatus(state.getGeneratedContent() != null ? StepStatus.GENERATED : StepStatus.NOT_STARTED);
                workflowStateRepository.save(state);
            }
        });
        // Reset chapter status
        if (chapterNumber > 0) {
            chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                if (ch.getStatus() == StepStatus.GENERATING) {
                    ch.setStatus(ch.getContent() != null ? StepStatus.GENERATED : StepStatus.NOT_STARTED);
                    chapterRepository.save(ch);
                }
            });
        }
    }

    /**
     * Generate and save titles for all chapters that have content.
     * Synchronous method suitable for calling from AutoRunService.
     */
    public void generateAndSaveTitles(Long projectId) {
        generateAndSaveTitles(projectId, () -> false);
    }

    /**
     * Generate and save titles with a stop check between each chapter.
     * @param shouldStop returns true if generation should be aborted
     */
    public void generateAndSaveTitles(Long projectId, java.util.function.BooleanSupplier shouldStop) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        AiProviderRouter.ResolvedModel resolved = resolveModelForProject(projectId);

        for (ChapterEntity ch : chapters) {
            if (shouldStop.getAsBoolean()) return;
            if (ch.getContent() == null || ch.getContent().isBlank()) continue;
            if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
                log.info("Chapter {} already has title '{}', skipping", ch.getChapterNumber(), ch.getTitle());
                continue;
            }

            String contentPreview = ch.getContent().length() > 1000
                    ? ch.getContent().substring(0, 1000) : ch.getContent();
            String titlePrompt = "请为以下小说章节内容生成一个简短的章节标题，要求：4-12个字，不要带第X章前缀，只输出标题文字，不要标点符号。\n\n"
                    + contentPreview;

            AiRequest request = AiRequest.builder()
                    .systemPrompt("你是一位小说编辑，擅长给章节起标题。要求每个标题控制在4-12个字，风格统一，长度尽量一致（建议6-8字）。只输出标题文字，不要任何额外内容。")
                    .userPrompt(titlePrompt)
                    .maxTokens(30)
                    .temperature(0.5)
                    .build();
            applyResolvedConfig(request, resolved);

            StringBuilder title = new StringBuilder();
            long titleStart = System.currentTimeMillis();
            resolved.provider().streamText(request)
                    .doOnNext(title::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - titleStart);

            String generatedTitle = title.toString().trim()
                    .replaceAll("[\"'\\n\\r]", "")
                    .replaceAll("[\u201c\u201d\u2018\u2019]", "");
            if (generatedTitle.length() > 12) {
                generatedTitle = generatedTitle.substring(0, 12);
            }
            ch.setTitle(generatedTitle);
            chapterRepository.save(ch);
            log.info("Generated title for chapter {}: {}", ch.getChapterNumber(), generatedTitle);
        }
    }

    // === Proofreading Logic ===

    private Flux<String> runProofreading(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<CharacterEntity> characters = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
        List<String> characterNames = characters.stream().map(CharacterEntity::getName).toList();

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        // Accumulate foreshadowing across chapters
        List<String> accumulatedForeshadowing = new ArrayList<>();

        Flux<String> proofreadFlux = Flux.fromIterable(chapters)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .concatMap(ch -> proofreadSingleChapter(ch, chapters, characterNames, characters, resolved, accumulatedForeshadowing));

        // After all chapters are proofread, run fix pass
        return proofreadFlux.concatWith(Flux.defer(() -> runProofreadingFix(projectId)));
    }

    private Flux<String> proofreadSingleChapter(ChapterEntity chapter,
                                                  List<ChapterEntity> allChapters,
                                                  List<String> characterNames,
                                                  List<CharacterEntity> characters,
                                                  AiProviderRouter.ResolvedModel resolved,
                                                  List<String> accumulatedForeshadowing) {
        int chNum = chapter.getChapterNumber();
        String content = chapter.getContent();

        // Sub-step results collected here
        AtomicReference<String> plotSummaryRef = new AtomicReference<>("");
        AtomicReference<String> characterIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> consistencyIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> continuityIssuesRef = new AtomicReference<>("[]");
        AtomicReference<String> foreshadowingRef = new AtomicReference<>("[]");

        // Resolve proofreading system prompt once
        String proofSysPrompt = getSystemPromptForStep(WorkflowStep.PROOFREADING);
        if (proofSysPrompt == null) {
            proofSysPrompt = "你是一位专业的小说校对编辑，擅长发现前后文矛盾和人名错误。";
        }
        final String proofSystemPrompt = proofSysPrompt;

        // Sub-step 1: Plot Summary
        Flux<String> step1 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":PLOT_SUMMARY]]")
                .concatWith(Flux.defer(() -> {
                    long s1Start = System.currentTimeMillis();
                    String prompt = "请提取以下小说章节的主要情节摘要，50-100字，只输出摘要文字，不要任何前缀或标注。\n\n" + wrapContent(truncate(content, 6000));
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(proofSystemPrompt)
                            .userPrompt(prompt)
                            .maxTokens(256)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                plotSummaryRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s1Start);
                            });
                }));

        // Sub-step 2: Character Check
        Flux<String> step2 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CHARACTER_CHECK]]")
                .concatWith(Flux.defer(() -> {
                    long s2Start = System.currentTimeMillis();
                    String nameList = String.join("、", characterNames);
                    String prompt = String.format("""
                            以下是小说正文和角色名单。请提取文中出现的所有人物姓名，与角色名单比对。
                            如发现疑似写错的姓名（音近、形近、别名等），输出JSON数组: [{"found":"错误名","should_be":"正确名","context":"出现位置的前后几个字"}]
                            如无问题输出空数组 []
                            只输出JSON，不要任何其他内容。

                            【角色名单】%s

                            【正文】
                            %s""", nameList, wrapContent(truncate(content, 6000)));
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(proofSystemPrompt)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.2)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                characterIssuesRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s2Start);
                            });
                }));

        // Sub-step 3: Consistency Check
        Flux<String> step3 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CONSISTENCY]]")
                .concatWith(Flux.defer(() -> {
                    long s3Start = System.currentTimeMillis();
                    // Build character summary for reference
                    StringBuilder charSummary = new StringBuilder();
                    for (CharacterEntity c : characters) {
                        if (c.getContent() != null) {
                            charSummary.append(c.getName()).append(": ")
                                    .append(truncate(c.getContent(), 200)).append("\n");
                        }
                    }
                    String prevSummary = plotSummaryRef.get();
                    // Also get previous chapter's plot summary if available
                    String prevChapterSummary = "";
                    if (chNum > 1) {
                        for (ChapterEntity prev : allChapters) {
                            if (prev.getChapterNumber() == chNum - 1 && prev.getPlotSummary() != null) {
                                prevChapterSummary = prev.getPlotSummary();
                                break;
                            }
                        }
                    }
                    String prompt = String.format("""
                            检查本章是否存在与人物设定或前文的矛盾（外貌/能力/关系/地名/时间线）。
                            输出JSON数组: [{"type":"矛盾类型","description":"具体描述","severity":"high/medium/low"}]
                            如无问题输出空数组 []
                            只输出JSON，不要任何其他内容。

                            【角色设定摘要】
                            %s

                            【前一章情节摘要】%s

                            【本章正文】
                            %s""", wrapContent(charSummary.toString()), prevChapterSummary, wrapContent(truncate(content, 5000)));
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(proofSystemPrompt)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                consistencyIssuesRef.set(sb.toString().trim());
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s3Start);
                            });
                }));

        // Sub-step 4: Continuity Check (skip for chapter 1)
        Flux<String> step4;
        if (chNum <= 1) {
            step4 = Flux.empty();
        } else {
            step4 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":CONTINUITY]]")
                    .concatWith(Flux.defer(() -> {
                        long s4Start = System.currentTimeMillis();
                        // Get previous chapter's last 200 chars
                        String prevEnd = "";
                        for (ChapterEntity prev : allChapters) {
                            if (prev.getChapterNumber() == chNum - 1 && prev.getContent() != null) {
                                String pc = prev.getContent();
                                prevEnd = pc.length() > 200 ? pc.substring(pc.length() - 200) : pc;
                                break;
                            }
                        }
                        String currStart = content.length() > 200 ? content.substring(0, 200) : content;
                        String prompt = String.format("""
                                判断以下两段文字（上一章结尾与本章开头）的衔接是否自然。
                                如有突兀的跳跃、重复或断裂，输出JSON数组: [{"prev_end":"上章结尾要点","curr_start":"本章开头要点","issue":"具体问题"}]
                                如衔接良好输出空数组 []
                                只输出JSON，不要任何其他内容。

                                【上一章结尾】
                                %s

                                【本章开头】
                                %s""", wrapContent(prevEnd), wrapContent(currStart));
                        AiRequest req = AiRequest.builder()
                                .systemPrompt(proofSystemPrompt)
                                .userPrompt(prompt)
                                .maxTokens(512)
                                .temperature(0.3)
                                .build();
                        applyResolvedConfig(req, resolved);
                        StringBuilder sb = new StringBuilder();
                        return resolved.provider().streamText(req)
                                .doOnNext(sb::append)
                                .doOnComplete(() -> {
                                    continuityIssuesRef.set(sb.toString().trim());
                                    aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s4Start);
                                });
                    }));
        }

        // Sub-step 5: Foreshadowing Tracking
        Flux<String> step5 = Flux.just("[[PROOFREAD:CHAPTER:" + chNum + ":FORESHADOWING]]")
                .concatWith(Flux.defer(() -> {
                    long s5Start = System.currentTimeMillis();
                    String prevForeshadowing = accumulatedForeshadowing.isEmpty() ? "无"
                            : String.join("\n", accumulatedForeshadowing);
                    String prompt = String.format("""
                            1. 提取本章新埋下的伏笔/悬念
                            2. 检查前文伏笔在本章是否已回收

                            输出JSON数组: [{"type":"planted或resolved","content":"伏笔/悬念内容描述","source_chapter":%d}]
                            - type为"planted"表示本章新埋下的伏笔
                            - type为"resolved"表示前文伏笔在本章被回收
                            如无伏笔输出空数组 []
                            只输出JSON，不要任何其他内容。

                            【前文已记录的伏笔】
                            %s

                            【本章正文（第%d章）】
                            %s""", chNum, prevForeshadowing, chNum, wrapContent(truncate(content, 5000)));
                    AiRequest req = AiRequest.builder()
                            .systemPrompt(proofSystemPrompt)
                            .userPrompt(prompt)
                            .maxTokens(1024)
                            .temperature(0.3)
                            .build();
                    applyResolvedConfig(req, resolved);
                    StringBuilder sb = new StringBuilder();
                    return resolved.provider().streamText(req)
                            .doOnNext(sb::append)
                            .doOnComplete(() -> {
                                String result = sb.toString().trim();
                                foreshadowingRef.set(result);
                                // Accumulate planted foreshadowing for future chapters
                                accumulatedForeshadowing.add("第" + chNum + "章: " + result);
                                aiUsageTracker.record(chapter.getProjectId(), resolved.modelId(), resolved.provider().getProviderName(), System.currentTimeMillis() - s5Start);
                            });
                }));

        // After all sub-steps, save results
        Flux<String> saveStep = Flux.defer(() -> {
            saveProofreadingResults(chapter, plotSummaryRef.get(), characterIssuesRef.get(),
                    consistencyIssuesRef.get(), continuityIssuesRef.get(), foreshadowingRef.get());
            return Flux.empty();
        });

        return step1.concatWith(step2).concatWith(step3).concatWith(step4).concatWith(step5).concatWith(saveStep);
    }

    private void saveProofreadingResults(ChapterEntity chapter, String plotSummary,
                                          String characterIssues, String consistencyIssues,
                                          String continuityIssues, String foreshadowing) {
        Long projectId = chapter.getProjectId();
        int chNum = chapter.getChapterNumber();

        // Save to proofreading_reports
        ProofreadingReportEntity report = proofreadingReportRepository
                .findByProjectIdAndChapterNumber(projectId, chNum)
                .orElseGet(() -> {
                    ProofreadingReportEntity r = new ProofreadingReportEntity();
                    r.setProjectId(projectId);
                    r.setChapterNumber(chNum);
                    return r;
                });
        report.setPlotSummary(plotSummary != null && plotSummary.length() > 500 ? plotSummary.substring(0, 500) : plotSummary);
        report.setCharacterIssues(characterIssues);
        report.setConsistencyIssues(consistencyIssues);
        report.setContinuityIssues(continuityIssues);
        report.setForeshadowing(foreshadowing);
        proofreadingReportRepository.save(report);

        // Update chapter entity
        chapter.setPlotSummary(plotSummary != null && plotSummary.length() > 500 ? plotSummary.substring(0, 500) : plotSummary);
        chapter.setProofreadStatus(StepStatus.GENERATED);
        chapterRepository.save(chapter);

        // Update chapter outline summary with actual plot summary
        chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chNum)
                .ifPresent(outline -> {
                    outline.setSummary(plotSummary);
                    chapterOutlineRepository.save(outline);
                });

        log.info("Saved proofreading results for project {} chapter {}", projectId, chNum);
    }

    public ProofreadingReportRepository getProofreadingReportRepository() {
        return proofreadingReportRepository;
    }

    // === Proofreading Fix Logic ===

    /**
     * Fix a single chapter based on its proofreading report.
     * Returns a Flux of tokens representing the fixed content.
     */
    public Flux<String> proofreadFixSingleChapter(Long projectId, int chapterNumber) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        ProofreadingReportEntity report = proofreadingReportRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Proofreading report not found for chapter: " + chapterNumber));

        String originalContent = chapter.getContent();
        if (originalContent == null || originalContent.isBlank()) {
            return Flux.error(new IllegalStateException("Chapter has no content to fix"));
        }

        // Save original content as backup
        chapter.setContentBeforeFix(originalContent);
        chapter.setProofreadFixStatus(StepStatus.GENERATING);
        chapterRepository.save(chapter);

        // Build proofreading report summary
        StringBuilder reportSummary = new StringBuilder();
        if (report.getCharacterIssues() != null && !report.getCharacterIssues().equals("[]")) {
            reportSummary.append("【角色校正问题】\n").append(report.getCharacterIssues()).append("\n\n");
        }
        if (report.getConsistencyIssues() != null && !report.getConsistencyIssues().equals("[]")) {
            reportSummary.append("【一致性问题】\n").append(report.getConsistencyIssues()).append("\n\n");
        }
        if (report.getContinuityIssues() != null && !report.getContinuityIssues().equals("[]")) {
            reportSummary.append("【衔接问题】\n").append(report.getContinuityIssues()).append("\n\n");
        }

        if (reportSummary.isEmpty()) {
            // No issues found, skip fix
            chapter.setProofreadFixStatus(StepStatus.GENERATED);
            chapterRepository.save(chapter);
            return Flux.just(originalContent);
        }

        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.PROOFREADING);

        String systemPrompt = "你是一位专业小说编辑，根据校对报告修改章节正文。只修改校对报告中指出的问题，保持原文的文风、情节和结构不变。直接输出修改后的完整章节正文，不要添加任何说明或标注。";
        String userPrompt = String.format("""
                请根据以下校对报告修改章节正文。只修正报告中指出的问题，不要改动其他内容。

                %s
                【原文】
                %s""", reportSummary.toString(), originalContent);

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(8192)
                .temperature(0.3)
                .build();
        applyResolvedConfig(request, resolved);

        StringBuilder fixedContent = new StringBuilder();
        long startTime = System.currentTimeMillis();

        return resolved.provider().streamText(request)
                .doOnNext(fixedContent::append)
                .doOnComplete(() -> {
                    aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(),
                            System.currentTimeMillis() - startTime);
                    // Save fixed content
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                        ch.setContent(fixedContent.toString());
                        ch.setWordCount(fixedContent.length());
                        ch.setProofreadFixStatus(StepStatus.GENERATED);
                        chapterRepository.save(ch);
                    });
                    log.info("[P{}] Proofread fix completed for chapter {}", projectId, chapterNumber);
                })
                .doOnError(e -> {
                    // Reset status on error
                    chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber).ifPresent(ch -> {
                        ch.setProofreadFixStatus(StepStatus.NOT_STARTED);
                        ch.setContent(originalContent);
                        chapterRepository.save(ch);
                    });
                    log.error("[P{}] Proofread fix failed for chapter {}: {}", projectId, chapterNumber, e.getMessage());
                });
    }

    /**
     * Synchronous version of proofreadFixSingleChapter for use by AutoRunService.
     */
    public void proofreadFixSingleChapterSync(Long projectId, int chapterNumber) {
        proofreadFixSingleChapter(projectId, chapterNumber).blockLast();
    }

    /**
     * Run proofreading fix for all chapters that have reports.
     * Emits [[PROOFREAD_FIX:CHAPTER:N]] markers between chapters.
     */
    private Flux<String> runProofreadingFix(Long projectId) {
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        List<ChapterEntity> needsFix = chapters.stream()
                .filter(ch -> ch.getProofreadStatus() == StepStatus.GENERATED || ch.getProofreadStatus() == StepStatus.CONFIRMED)
                .filter(ch -> ch.getProofreadFixStatus() != StepStatus.GENERATED && ch.getProofreadFixStatus() != StepStatus.CONFIRMED)
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .toList();

        if (needsFix.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromIterable(needsFix)
                .concatMap(ch -> Flux.just("[[PROOFREAD_FIX:CHAPTER:" + ch.getChapterNumber() + "]]")
                        .concatWith(Flux.defer(() -> {
                            // Check if report exists, skip if no issues
                            var reportOpt = proofreadingReportRepository
                                    .findByProjectIdAndChapterNumber(projectId, ch.getChapterNumber());
                            if (reportOpt.isEmpty()) {
                                return Flux.empty();
                            }
                            return proofreadFixSingleChapter(projectId, ch.getChapterNumber());
                        })));
    }

    private void updateStepStatus(Long projectId, WorkflowStep step, StepStatus status) {
        WorkflowStateEntity state = workflowStateRepository
                .findByProjectIdAndStep(projectId, step)
                .orElseGet(() -> {
                    WorkflowStateEntity s = new WorkflowStateEntity();
                    s.setProjectId(projectId);
                    s.setStep(step);
                    return s;
                });
        state.setStatus(status);
        workflowStateRepository.save(state);
    }

    private void saveWorldSetting(Long projectId, String content) {
        WorldSettingEntity ws = worldSettingRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    WorldSettingEntity w = new WorldSettingEntity();
                    w.setProjectId(projectId);
                    return w;
                });
        ws.setContent(content);
        String summary = contextSummaryService.summarizeWorldSetting(projectId, content);
        if (summary != null) {
            ws.setSummary(summary);
        }
        worldSettingRepository.save(ws);
    }

    private void saveCharacters(Long projectId, String content) {
        // Parse content into individual character cards + overview (summary last)
        characterRepository.deleteByProjectId(projectId);

        if (content == null || content.isBlank()) return;

        String[] segments = content.split("\\n+\\s*-{3,}\\s*\\n+");

        // Find the overview segment (contains "## 角色总览")
        int overviewIdx = -1;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].contains("## 角色总览")) {
                overviewIdx = i;
                break;
            }
        }

        // Save overview as sort_order=0
        if (overviewIdx >= 0) {
            String overviewContent = segments[overviewIdx]
                    .replaceFirst("(?s).*?## 角色总览\\s*", "").strip();
            CharacterEntity overview = new CharacterEntity();
            overview.setProjectId(projectId);
            overview.setName("全部角色");
            overview.setSortOrder(0);
            overview.setContent(overviewContent);
            String overviewSummary = contextSummaryService.summarizeCharacterOverview(projectId, overviewContent);
            if (overviewSummary != null) overview.setSummary(overviewSummary);
            characterRepository.save(overview);
        }

        // Save character cards (all segments except the overview)
        int sortOrder = 1;
        for (int i = 0; i < segments.length; i++) {
            if (i == overviewIdx) continue;
            String segment = segments[i].replaceFirst("###\\s*角色\\d+\\s*\\n*", "").strip();
            if (segment.isBlank()) continue;

            CharacterEntity card = new CharacterEntity();
            card.setProjectId(projectId);
            card.setSortOrder(sortOrder);
            card.setContent(segment);
            card.setStatus("GENERATED");

            // Extract structured fields from 【bracket】 format
            card.setName(truncateNullable(extractField(segment, "姓名"), 100));
            if (card.getName() == null || card.getName().isBlank()) card.setName("角色" + sortOrder);
            card.setGender(truncateNullable(extractField(segment, "性别"), 20));
            card.setAge(truncateNullable(extractField(segment, "年龄"), 20));
            card.setRole(truncateNullable(extractField(segment, "身份"), 50));
            card.setPersonality(truncateNullable(extractField(segment, "性格"), 500));
            card.setAppearance(truncateNullable(extractField(segment, "外貌"), 500));
            card.setBackground(extractField(segment, "背景"));
            card.setMotivation(truncateNullable(extractField(segment, "动机"), 500));
            card.setAbilities(truncateNullable(extractField(segment, "能力"), 500));
            card.setRelationships(truncateNullable(extractField(segment, "关系"), 500));

            String cardSummary = contextSummaryService.summarizeCharacterCard(projectId, segment);
            if (cardSummary != null) card.setSummary(cardSummary);

            characterRepository.save(card);
            sortOrder++;
        }
    }

    private String cleanMarkdownForParsing(String text) {
        if (text == null) return null;
        // Remove bold/italic markdown markers
        String cleaned = text.replaceAll("\\*{1,3}", "");
        cleaned = cleaned.replaceAll("_{1,3}", "");
        // Remove heading markers
        cleaned = cleaned.replaceAll("(?m)^#{1,6}\\s*", "");
        // Ensure each 【 starts on a new line
        cleaned = cleaned.replaceAll("(?<!^)(?<!\\n)【", "\n【");
        return cleaned;
    }

    private String extractField(String text, String fieldName) {
        String cleaned = cleanMarkdownForParsing(text);
        if (cleaned == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "【" + fieldName + "】[：:]?\\s*(.+?)(?=\\n【|\\z)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            String result = matcher.group(1).trim();
            // Remove any residual markdown markers
            result = result.replaceAll("\\*+", "");
            return result;
        }
        return null;
    }

    private String truncateNullable(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private void saveOutline(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        // Check if content contains section markers (new format)
        if (content.contains("[[SECTION:")) {
            saveOutlineWithMarkers(projectId, content);
        } else {
            // Legacy format: save raw content to story_outlines and parse chapter outlines
            saveOutlineLegacy(projectId, content);
        }
    }

    private void saveOutlineWithMarkers(Long projectId, String content) {
        // Clear existing data
        volumeOutlineRepository.deleteByProjectId(projectId);
        chapterOutlineRepository.deleteByProjectId(projectId);

        Pattern sectionPattern = Pattern.compile("\\[\\[SECTION:([A-Z]+)(?::([^\\]]+))?\\]\\]");
        Matcher matcher = sectionPattern.matcher(content);

        // Split content by markers
        List<String> types = new ArrayList<>();
        List<String> params = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        int lastEnd = 0;
        while (matcher.find()) {
            types.add(matcher.group(1));
            params.add(matcher.group(2));
            // Text before this marker (skip for first)
            if (!types.isEmpty() && lastEnd > 0) {
                texts.add(content.substring(lastEnd, matcher.start()).strip());
            } else if (lastEnd == 0 && matcher.start() > 0) {
                // Text before first marker - discard
            }
            lastEnd = matcher.end();
        }
        // Last segment
        if (lastEnd < content.length()) {
            texts.add(content.substring(lastEnd).strip());
        }

        // Ensure texts aligns with types (each marker followed by its text)
        // Re-parse with a simpler approach:
        String[] sections = content.split("\\[\\[SECTION:");
        String storySummaryText = null;

        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        for (String section : sections) {
            if (section.isBlank()) continue;

            // Extract the marker info and text
            int closeBracket = section.indexOf("]]");
            if (closeBracket < 0) continue;
            String markerInfo = section.substring(0, closeBracket);
            String text = section.substring(closeBracket + 2).strip();

            String[] markerParts = markerInfo.split(":");

            if (markerParts[0].equals("VOLUME") && markerParts.length >= 4) {
                int volNum = Integer.parseInt(markerParts[1]);
                int chStart = Integer.parseInt(markerParts[2]);
                int chEnd = Integer.parseInt(markerParts[3]);

                VolumeOutlineEntity vol = new VolumeOutlineEntity();
                vol.setProjectId(projectId);
                vol.setVolumeNumber(volNum);
                vol.setChapterStart(chStart);
                vol.setChapterEnd(chEnd);
                vol.setArcSummary(text);
                vol.setTitle("第" + volNum + "卷");
                volumeOutlineRepository.save(vol);

            } else if (markerParts[0].equals("CHAPTER") && markerParts.length >= 3) {
                int chNum = Integer.parseInt(markerParts[1]);
                int volNum = Integer.parseInt(markerParts[2]);

                String title = null;
                Matcher tMatcher = titlePattern.matcher(text);
                if (tMatcher.find()) {
                    title = tMatcher.group(1).trim();
                    if (title.length() > 200) title = title.substring(0, 200);
                }

                String characterNames = null;
                Matcher cMatcher = characterPattern.matcher(text);
                if (cMatcher.find()) {
                    characterNames = cMatcher.group(1).trim();
                    if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
                }

                String summary = text
                        .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                        .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                        .strip();

                ChapterOutlineEntity entity = new ChapterOutlineEntity();
                entity.setProjectId(projectId);
                entity.setChapterNumber(chNum);
                entity.setVolumeNumber(volNum);
                entity.setTitle(title != null ? title : "第" + chNum + "章");
                entity.setSummary(summary);
                entity.setCharacterNames(characterNames);
                entity.setStatus("COMPLETED");
                chapterOutlineRepository.save(entity);

            } else if (markerParts[0].equals("SUMMARY")) {
                storySummaryText = text;
            }
        }

        // Save story summary to story_outlines
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(storySummaryText != null ? storySummaryText : "");
        storyOutlineRepository.save(outline);
    }

    private void saveOutlineLegacy(Long projectId, String content) {
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity o = new StoryOutlineEntity();
                    o.setProjectId(projectId);
                    return o;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);

        // Parse and save individual chapter outlines (legacy format)
        parseAndSaveChapterOutlinesLegacy(projectId, content);
    }

    private void parseAndSaveChapterOutlinesLegacy(Long projectId, String content) {
        if (content == null || content.isBlank()) return;

        chapterOutlineRepository.deleteByProjectId(projectId);

        String[] segments = content.split("\\n+\\s*-{3,}\\s*\\n+");
        Pattern chapterPattern = Pattern.compile("###\\s*第(\\d+)章");
        Pattern titlePattern = Pattern.compile("\\*\\*标题[：:]\\*\\*\\s*(.+)");
        Pattern characterPattern = Pattern.compile("\\*\\*出场角色[：:]\\*\\*\\s*(.+)");

        for (String segment : segments) {
            Matcher chMatcher = chapterPattern.matcher(segment);
            if (!chMatcher.find()) continue;
            int chapterNumber = Integer.parseInt(chMatcher.group(1));

            String title = null;
            Matcher tMatcher = titlePattern.matcher(segment);
            if (tMatcher.find()) {
                title = tMatcher.group(1).trim();
                if (title.length() > 200) title = title.substring(0, 200);
            }

            String characterNames = null;
            Matcher cMatcher = characterPattern.matcher(segment);
            if (cMatcher.find()) {
                characterNames = cMatcher.group(1).trim();
                if (characterNames.length() > 500) characterNames = characterNames.substring(0, 500);
            }

            String summary = segment
                    .replaceFirst("###\\s*第\\d+章[^\\n]*\\n?", "")
                    .replaceFirst("\\*\\*标题[：:]\\*\\*[^\\n]*\\n?", "")
                    .replaceFirst("\\*\\*出场角色[：:]\\*\\*[^\\n]*\\n?", "")
                    .strip();

            ChapterOutlineEntity entity = new ChapterOutlineEntity();
            entity.setProjectId(projectId);
            entity.setChapterNumber(chapterNumber);
            entity.setTitle(title != null ? title : "第" + chapterNumber + "章");
            entity.setSummary(summary);
            entity.setCharacterNames(characterNames);
            entity.setStatus("COMPLETED");
            chapterOutlineRepository.save(entity);
        }
    }

    private void saveChapter(Long projectId, int chapterNumber, String content) {
        if (chapterNumber <= 0) return;
        ChapterEntity chapter = chapterRepository
                .findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseGet(() -> {
                    ChapterEntity c = new ChapterEntity();
                    c.setProjectId(projectId);
                    c.setChapterNumber(chapterNumber);
                    return c;
                });
        chapter.setContent(content);
        chapter.setWordCount(content != null ? content.length() : 0);
        chapter.setStatus(StepStatus.GENERATED);
        // Apply outline title if chapter has no title
        if (chapter.getTitle() == null || chapter.getTitle().isBlank()) {
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .map(ChapterOutlineEntity::getTitle)
                    .filter(t -> t != null && !t.isBlank())
                    .ifPresent(chapter::setTitle);
        }
        chapterRepository.save(chapter);
    }

    // === Character Refine ===

    public Flux<String> refineAllCharacters(Long projectId) {
        // Check project status
        ProjectEntity proj = projectRepository.findById(projectId).orElse(null);
        if (proj != null && proj.getStatus() != null) {
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.COMPLETED) {
                return Flux.error(new IllegalStateException("项目状态为「已完本」，无法进行AI生成"));
            }
            if (proj.getStatus() == com.storycreator.core.domain.ProjectStatus.ABANDONED) {
                return Flux.error(new IllegalStateException("项目状态为「已废弃」，无法进行AI生成"));
            }
        }

        List<CharacterEntity> cards = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0)
                .stream()
                .filter(c -> !"REFINING".equals(c.getStatus()))
                .toList();

        if (cards.isEmpty()) {
            return Flux.just("[[CHAR:REFINE:DONE]]");
        }

        // Build summaries list for context
        List<String> allSummaries = cards.stream()
                .map(c -> c.getName() + " - " + (c.getRole() != null ? c.getRole() : "") + " - " + (c.getPersonality() != null ? c.getPersonality() : ""))
                .toList();

        // Load world setting and project info
        WorkflowContext ctx = buildContext(projectId, 0);
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);

        return Flux.range(0, cards.size())
                .concatMap(idx -> {
                    CharacterEntity card = cards.get(idx);
                    // Mark as REFINING
                    card.setStatus("REFINING");
                    characterRepository.save(card);

                    Flux<String> marker = Flux.just("[[CHAR:REFINE:" + (idx + 1) + "]]");
                    StringBuilder refined = new StringBuilder();

                    Flux<String> refineFlux = generateSingleRefine(ctx, card, allSummaries, resolved)
                            .doOnNext(refined::append)
                            .doOnComplete(() -> {
                                String text = refined.toString();
                                // Parse refined fields and update
                                card.setContent(text);
                                card.setName(truncateNullable(extractField(text, "姓名"), 100));
                                if (card.getName() == null || card.getName().isBlank()) card.setName("角色" + card.getSortOrder());
                                card.setGender(truncateNullable(extractField(text, "性别"), 20));
                                card.setAge(truncateNullable(extractField(text, "年龄"), 20));
                                card.setRole(truncateNullable(extractField(text, "身份"), 50));
                                card.setPersonality(truncateNullable(extractField(text, "性格"), 500));
                                card.setAppearance(truncateNullable(extractField(text, "外貌"), 500));
                                card.setBackground(extractField(text, "背景"));
                                card.setMotivation(truncateNullable(extractField(text, "动机"), 500));
                                card.setAbilities(truncateNullable(extractField(text, "能力"), 500));
                                card.setRelationships(truncateNullable(extractField(text, "关系"), 500));
                                // Extract summary field
                                String summaryField = extractField(text, "概要");
                                if (summaryField != null && !summaryField.isBlank()) {
                                    card.setSummary(truncateNullable(summaryField, 300));
                                } else {
                                    // Fallback: use AI to summarize
                                    String summary = contextSummaryService.summarizeCharacterCard(projectId, text);
                                    if (summary != null) card.setSummary(summary);
                                }
                                card.setStatus("REFINED");
                                characterRepository.save(card);
                                log.info("[P{}] Character {} refined: {}", projectId, card.getSortOrder(), card.getName());
                            });

                    return marker.concatWith(refineFlux);
                })
                .concatWith(Flux.just("[[CHAR:REFINE:DONE]]"));
    }

    private Flux<String> generateSingleRefine(WorkflowContext ctx, CharacterEntity card,
                                               List<String> allSummaries, AiProviderRouter.ResolvedModel resolved) {
        StringBuilder summariesText = new StringBuilder();
        for (int i = 0; i < allSummaries.size(); i++) {
            summariesText.append(i + 1).append(". ").append(allSummaries.get(i)).append("\n");
        }

        String prompt = String.format("""
                你是一位资深网络小说角色设计师。请对以下角色卡片进行精修，基于全局世界观和其他角色信息进行一致性校验和细节丰富。

                【小说信息】标题：%s，题材：%s
                简介：%s
                【世界观摘要】%s

                【全部角色概要】
                %s

                【当前角色完整卡片】
                %s

                精修要求：
                1. 校验与世界观一致性（设定、能力体系、时代背景等）
                2. 校验与其他角色关系描述互相呼应
                3. 丰富细节，使人物更加立体（增加具体事例、细节描写）
                4. 保持核心设定不变（姓名、性别、基本身份）

                请严格按以下纯文本格式输出精修后的角色信息卡：

                【姓名】角色全名
                【性别】男/女/其他
                【年龄】具体数字或描述性年龄
                【身份】角色在故事中的身份/职业
                【性格】2-3个关键词加简短描述
                【外貌】简洁的外貌特征描述
                【背景】角色的前史和来历
                【动机】角色在故事中的核心驱动力
                【能力】角色的特长或能力
                【关系】与其他角色的关键关系
                【概要】用不超过300字概括该角色的核心定位、在故事中的作用和关键特征

                注意：
                1. 禁止使用markdown格式（如**加粗**、#标题等），只用纯文本。
                2. 每个【字段】必须另起一行。
                3. 用中文输出。
                """,
                ctx.getTitle(),
                ctx.getGenre() != null ? ctx.getGenre().getDisplayName() : "",
                ctx.getDescription() != null ? ctx.getDescription() : "",
                truncate(ctx.getWorldSetting(), 600),
                summariesText.toString(),
                card.getContent() != null ? card.getContent() : "(无内容)");

        String systemPrompt = getSystemPromptForStep(WorkflowStep.CHARACTER_DESIGN);
        if (systemPrompt == null) {
            systemPrompt = "你是一位网络小说角色设计师，请精修角色信息卡，确保一致性和细节丰富。";
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(2048)
                .temperature(0.7)
                .build();
        applyResolvedConfig(request, resolved);

        return resolved.provider().streamText(request);
    }
}
