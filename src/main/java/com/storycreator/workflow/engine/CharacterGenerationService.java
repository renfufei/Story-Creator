package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.StepGuidanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

import static com.storycreator.workflow.engine.TextProcessingUtils.*;

@Service
public class CharacterGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CharacterGenerationService.class);

    private final ProjectRepository projectRepository;
    private final CharacterRepository characterRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final WorkflowContextBuilder contextBuilder;
    private final ContextSummaryService contextSummaryService;
    private final AiUsageTracker aiUsageTracker;

    public CharacterGenerationService(ProjectRepository projectRepository,
                                      CharacterRepository characterRepository,
                                      StepGuidanceRepository stepGuidanceRepository,
                                      AiProviderRouter providerRouter,
                                      PromptTemplateRegistry promptRegistry,
                                      WorkflowContextBuilder contextBuilder,
                                      ContextSummaryService contextSummaryService,
                                      AiUsageTracker aiUsageTracker) {
        this.projectRepository = projectRepository;
        this.characterRepository = characterRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.contextBuilder = contextBuilder;
        this.contextSummaryService = contextSummaryService;
        this.aiUsageTracker = aiUsageTracker;
    }

    public Flux<String> generateCharactersByCards(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        int characterCount = project.getCharacterCount();
        WorkflowContext baseContext = contextBuilder.build(projectId, 0);
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);

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
                    Flux<String> marker = Flux.just("[[CHAR:CARD:" + cardNum + "]]");
                    Flux<String> header = cardNum > 1
                        ? Flux.just("\n\n---\n\n### 角色" + cardNum + "\n\n")
                        : Flux.just("### 角色" + cardNum + "\n\n");

                    if (completedCardNums.contains(cardNum)) {
                        log.debug("[P{}] Character {} skipped (already exists)", projectId, cardNum);
                        String existing = existingCards.stream()
                            .filter(c -> c.getSortOrder() == cardNum)
                            .map(CharacterEntity::getContent).findFirst().orElse("");
                        return marker.concatWith(header).concatWith(Flux.just(existing));
                    }

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
            characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> c.getSortOrder() == 0)
                .findFirst()
                .ifPresent(c -> characterRepository.delete(c));

            Flux<String> marker = Flux.just("[[CHAR:OVERVIEW]]");
            Flux<String> header = Flux.just("\n\n---\n\n## 角色总览\n\n");
            StringBuilder overviewContent = new StringBuilder();
            long overviewStart = System.currentTimeMillis();
            String summaryPrompt = buildSummaryPrompt(baseContext, previousSummaries, guidanceSuffix);
            String overviewSysPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_OVERVIEW, baseContext.getGenre());
            if (overviewSysPrompt == null || overviewSysPrompt.isBlank()) {
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

        List<CharacterEntity> allCards = characterRepository
                .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);

        List<CharacterEntity> cards = allCards.stream()
                .filter(c -> !"REFINED".equals(c.getStatus()))
                .toList();

        if (cards.isEmpty()) {
            return Flux.just("[[CHAR:REFINE:DONE]]");
        }

        List<String> allSummaries = new ArrayList<>();
        for (int i = 0; i < allCards.size(); i++) {
            allSummaries.add(buildCharacterRefineEntry(allCards.get(i), i + 1));
        }

        WorkflowContext ctx = contextBuilder.build(projectId, 0);
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.CHARACTER_DESIGN);

        String guidanceSuffix = stepGuidanceRepository.findByProjectIdAndStep(projectId, WorkflowStep.CHARACTER_DESIGN)
                .filter(sg -> sg.getGuidance() != null && !sg.getGuidance().isBlank())
                .map(sg -> "\n\n【创作指导】\n" + sg.getGuidance() + "\n请在生成时参考以上指导意见。")
                .orElse("");

        return Flux.range(0, cards.size())
                .concatMap(idx -> {
                    CharacterEntity card = cards.get(idx);
                    card.setStatus("REFINING");
                    characterRepository.save(card);

                    Flux<String> marker = Flux.just("[[CHAR:REFINE:" + (idx + 1) + "]]");
                    StringBuilder refined = new StringBuilder();

                    Flux<String> refineFlux = generateSingleRefine(ctx, card, allSummaries, resolved, guidanceSuffix)
                            .doOnNext(refined::append)
                            .doOnComplete(() -> {
                                String text = stripAiFormatting(refined.toString());
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
                                String summaryField = extractField(text, "概要");
                                if (summaryField != null && !summaryField.isBlank()) {
                                    card.setSummary(truncateNullable(summaryField, 300));
                                } else {
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

    // --- Private helpers ---

    private void saveSingleCharacter(Long projectId, int sortOrder, String content) {
        content = stripAiFormatting(content);
        CharacterEntity card = new CharacterEntity();
        card.setProjectId(projectId);
        card.setSortOrder(sortOrder);
        card.setContent(content);
        card.setStatus("GENERATED");

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

        String summary = contextSummaryService.summarizeCharacterCard(projectId, content);
        if (summary != null) {
            card.setSummary(summary);
        }

        characterRepository.save(card);
        log.info("Saved character card {} for project {}: {}", sortOrder, projectId, card.getName());
    }

    private void saveCharacterOverview(Long projectId, String content) {
        content = stripAiFormatting(content);
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
            previousContext.append("\n【差异化要求】新角色必须满足：\n");
            previousContext.append("- 身份/职业不能与以上任何角色相同或近似\n");
            previousContext.append("- 性格特质至少有一个核心维度与已有角色形成对比\n");
            previousContext.append("- 能力体系不能重复（如已有剑修，不要再出剑修）\n");
            previousContext.append("- 动机和背景应开辟新的叙事方向\n");
            previousContext.append("- 与已有角色建立具体的关系纽带（师徒/对手/盟友等）\n");
        }

        Genre genre = baseContext.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, genre);
        Map<String, String> vars = Map.of(
                "title", baseContext.getTitle() != null ? baseContext.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "description", baseContext.getDescription() != null ? baseContext.getDescription() : "",
                "worldSetting", wrapContent(truncate(baseContext.getWorldSetting(), 400)),
                "previousContext", previousContext.toString(),
                "cardNumber", String.valueOf(cardNum),
                "totalCards", String.valueOf(totalCards),
                "stepGuidance", guidanceSuffix
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
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
        String gender = extractField(cardContent, "性别");
        String role = extractField(cardContent, "身份");
        String personality = truncate(extractField(cardContent, "性格"), 30);
        String motivation = truncate(extractField(cardContent, "动机"), 30);
        String abilities = truncate(extractField(cardContent, "能力"), 30);
        String background = truncate(extractField(cardContent, "背景"), 30);

        StringBuilder sb = new StringBuilder();
        sb.append(name != null ? name : "?");
        sb.append(" | ").append(gender != null ? gender : "?");
        sb.append(" | ").append(role != null ? role : "?");
        if (personality != null && !personality.isBlank()) {
            sb.append(" | 性格：").append(personality);
        }
        if (abilities != null && !abilities.isBlank()) {
            sb.append(" | 能力：").append(abilities);
        }
        if (motivation != null && !motivation.isBlank()) {
            sb.append(" | 动机：").append(motivation);
        }
        if (background != null && !background.isBlank()) {
            sb.append(" | 背景：").append(background);
        }
        return sb.toString();
    }

    private String buildCharacterRefineEntry(CharacterEntity c, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(". ");
        sb.append(c.getName() != null ? c.getName() : "角色" + c.getSortOrder());
        sb.append("（");
        sb.append(c.getGender() != null ? c.getGender() : "?");
        if (c.getAge() != null && !c.getAge().isBlank()) {
            sb.append("·").append(c.getAge());
        }
        sb.append("）| ").append(c.getRole() != null ? c.getRole() : "?");
        sb.append("\n");
        if (c.getPersonality() != null && !c.getPersonality().isBlank()) {
            sb.append("   性格：").append(truncate(c.getPersonality(), 50)).append("\n");
        }
        if (c.getMotivation() != null && !c.getMotivation().isBlank()) {
            sb.append("   动机：").append(truncate(c.getMotivation(), 40)).append("\n");
        }
        if (c.getAbilities() != null && !c.getAbilities().isBlank()) {
            sb.append("   能力：").append(truncate(c.getAbilities(), 30)).append("\n");
        }
        if (c.getRelationships() != null && !c.getRelationships().isBlank()) {
            sb.append("   关系：").append(truncate(c.getRelationships(), 60)).append("\n");
        }
        return sb.toString();
    }

    private String buildSummaryPrompt(WorkflowContext ctx, List<String> summaries, String guidanceSuffix) {
        Genre genre = ctx.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_OVERVIEW, genre);
        StringBuilder summariesText = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            summariesText.append(i + 1).append(". ").append(summaries.get(i)).append("\n");
        }
        Map<String, String> vars = Map.of(
                "title", ctx.getTitle() != null ? ctx.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "description", ctx.getDescription() != null ? ctx.getDescription() : "",
                "previousSummaries", summariesText.toString(),
                "stepGuidance", guidanceSuffix != null ? guidanceSuffix : ""
        );
        return promptRegistry.resolveTemplate(template, vars);
    }

    private Flux<String> generateSingleRefine(WorkflowContext ctx, CharacterEntity card,
                                               List<String> allSummaries, AiProviderRouter.ResolvedModel resolved, String guidanceSuffix) {
        String summariesText = String.join("\n", allSummaries);

        Genre genre = ctx.getGenre();
        String template = promptRegistry.getSubStepTemplate(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_REFINE, genre);
        Map<String, String> vars = Map.of(
                "title", ctx.getTitle() != null ? ctx.getTitle() : "",
                "genre", genre != null ? genre.getDisplayName() : "",
                "description", ctx.getDescription() != null ? ctx.getDescription() : "",
                "worldSetting", truncate(ctx.getWorldSetting(), 600),
                "allSummaries", summariesText,
                "cardContent", card.getContent() != null ? card.getContent() : "(无内容)",
                "stepGuidance", guidanceSuffix != null ? guidanceSuffix : ""
        );
        String prompt = promptRegistry.resolveTemplate(template, vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_REFINE, genre);
        if (systemPrompt == null || systemPrompt.isBlank()) {
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
