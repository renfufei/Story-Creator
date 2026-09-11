package com.storycreator.txtimport;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.engine.AiUsageTracker;
import com.storycreator.workflow.engine.VolumeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.storycreator.workflow.engine.TextProcessingUtils.applyResolvedConfig;

/**
 * TXT 导入的逆向工程：专用流程控制 + 实时下发 + 断点续跑。
 *
 * <p><b>流程</b>（逐级汇总，每级只依赖上一级的产出）：
 * <ol>
 *   <li>{@link RePhase#CHAPTER_OUTLINE} 逐章推断【章节大纲 + 本章角色】→ chapter_outlines</li>
 *   <li>{@link RePhase#STORY_ARC} 按每卷章节数分组，由本卷章节大纲推断【故事弧线】→ volume_outlines</li>
 *   <li>{@link RePhase#WORLD} / {@link RePhase#CHARACTERS} / {@link RePhase#STORY_OUTLINE}
 *       由全部故事弧线分别汇总【世界观】【角色】【故事总纲】</li>
 * </ol>
 *
 * <p><b>流程控制</b>：每个阶段的执行状态持久化在 {@code txt_import_re_steps}，
 * 完成度以「产出物是否存在」为最终依据（见 {@link #plan(Long)}）。
 *
 * <p><b>断点续跑</b>：{@link #runReverseEngineering} 每次执行前都会重新扫描完成度，
 * 已完成单元直接跳过（不发 LLM 请求），只补做剩余部分；进程重启或用户手动停止后再次执行即可继续。
 *
 * <p><b>上下文控制</b>：每次调用大模型前估算上下文长度，超过 {@link #MAX_CONTEXT_CHARS}（32K）
 * 时把输入拆成多个小节分别调用，再按原始顺序合并（章节顺序不变、角色去重）。
 */
@Service
public class TxtReverseEngineeringService {

    private static final Logger log = LoggerFactory.getLogger(TxtReverseEngineeringService.class);

    /** 单次调用允许的最大上下文字符数（32K）。 */
    private static final int MAX_CONTEXT_CHARS = 32 * 1024;

    /** 默认每卷章节数。 */
    private static final int DEFAULT_CHAPTERS_PER_VOLUME = 30;

    /** 阶段 3 汇总结果的角色记录名。 */
    private static final String REVERSE_CHARACTER_NAME = "逆向角色汇总";

    private final TxtImportJobRepository jobRepository;
    private final TxtImportChapterRepository importChapterRepository;
    private final TxtImportReStepRepository reStepRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final AiProviderRouter providerRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final AiUsageTracker aiUsageTracker;

    public TxtReverseEngineeringService(TxtImportJobRepository jobRepository,
                                        TxtImportChapterRepository importChapterRepository,
                                        TxtImportReStepRepository reStepRepository,
                                        ChapterOutlineRepository chapterOutlineRepository,
                                        VolumeOutlineRepository volumeOutlineRepository,
                                        WorldSettingRepository worldSettingRepository,
                                        CharacterRepository characterRepository,
                                        StoryOutlineRepository storyOutlineRepository,
                                        AiProviderRouter providerRouter,
                                        PromptTemplateRegistry promptRegistry,
                                        AiUsageTracker aiUsageTracker) {
        this.jobRepository = jobRepository;
        this.importChapterRepository = importChapterRepository;
        this.reStepRepository = reStepRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.providerRouter = providerRouter;
        this.promptRegistry = promptRegistry;
        this.aiUsageTracker = aiUsageTracker;
    }

    // ==================================================================
    // 启动清理：把上次进程遗留的「执行中」状态复位，保证可恢复
    // ==================================================================

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetInterruptedSteps() {
        int steps = reStepRepository.resetAllRunningToPending();
        if (steps > 0) {
            log.info("已复位 {} 个中断的逆向工程步骤为待执行", steps);
        }
    }

    // ==================================================================
    // 流程控制：扫描完成度
    // ==================================================================

    /**
     * 扫描任务当前完成度。
     * <p>完成度<b>以产出物为准</b>（chapter_outlines / volume_outlines / world_setting /
     * character / story_outline），因此用户手工改动数据或进程重启后依然准确。
     */
    public ReProtocol.RePlan plan(Long jobId) {
        TxtImportJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        return plan(job);
    }

    public ReProtocol.RePlan plan(TxtImportJobEntity job) {
        Long jobId = job.getId();
        Long projectId = job.getProjectId();
        int totalChapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId).size();
        int perVolume = perVolume(job);
        int totalVolumes = computeVolumes(totalChapters, perVolume).size();
        boolean needArc = job.isRunWorldBuilding() || job.isRunCharacters() || job.isRunOutline();

        // ---- 扫描产出物 ----
        int doneChapters = 0;
        int doneVolumes = 0;
        boolean worldDone = false;
        boolean charsDone = false;
        boolean outlineDone = false;

        if (projectId != null) {
            for (ChapterOutlineEntity co : chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)) {
                if (hasText(co.getSummary())) doneChapters++;
            }
            for (VolumeOutlineEntity vo : volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)) {
                if (hasText(vo.getArcSummary())) doneVolumes++;
            }
            worldDone = worldSettingRepository.findByProjectId(projectId)
                    .map(w -> hasText(w.getContent())).orElse(false);
            charsDone = characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                    .anyMatch(c -> REVERSE_CHARACTER_NAME.equals(c.getName()) && hasText(c.getContent()));
            outlineDone = storyOutlineRepository.findByProjectId(projectId)
                    .map(o -> hasText(o.getContent())).orElse(false);
        }

        List<ReProtocol.PhasePlan> phases = new ArrayList<>();
        phases.add(phasePlan(RePhase.CHAPTER_OUTLINE, totalChapters, doneChapters, true));
        phases.add(phasePlan(RePhase.STORY_ARC, totalVolumes, doneVolumes, needArc));
        phases.add(phasePlan(RePhase.WORLD, 1, worldDone ? 1 : 0, job.isRunWorldBuilding()));
        phases.add(phasePlan(RePhase.CHARACTERS, 1, charsDone ? 1 : 0, job.isRunCharacters()));
        phases.add(phasePlan(RePhase.STORY_OUTLINE, 1, outlineDone ? 1 : 0, job.isRunOutline()));

        int totalUnits = 0;
        int completedUnits = 0;
        boolean allDone = true;
        boolean anyProgress = false;
        for (ReProtocol.PhasePlan p : phases) {
            if (!p.runnable()) continue;
            totalUnits += p.totalUnits();
            completedUnits += p.completedUnits();
            if (p.completedUnits() > 0) anyProgress = true;
            if (p.completedUnits() < p.totalUnits()) allDone = false;
        }

        String jobStatus = job.getStatus() == null ? "PENDING" : job.getStatus();
        boolean running = jobStatus.startsWith("RE_") || "SPLITTING".equals(jobStatus);
        String effectiveStatus = running ? "RUNNING" : jobStatus;

        return new ReProtocol.RePlan(
                jobId, projectId, effectiveStatus, perVolume,
                totalChapters, totalVolumes, phases,
                totalUnits, completedUnits,
                anyProgress && !allDone,
                totalUnits > 0 && allDone);
    }

    private ReProtocol.PhasePlan phasePlan(RePhase phase, int total, int done, boolean runnable) {
        int boundedTotal = Math.max(0, total);
        int boundedDone = Math.min(Math.max(0, done), boundedTotal);
        String status;
        if (!runnable) {
            status = boundedDone > 0 ? RePhase.Status.COMPLETED.name() : RePhase.Status.SKIPPED.name();
        } else if (boundedDone >= boundedTotal) {
            status = RePhase.Status.COMPLETED.name();
        } else if (boundedDone > 0) {
            status = "PARTIAL";
        } else {
            status = RePhase.Status.PENDING.name();
        }
        return new ReProtocol.PhasePlan(phase.name(), phase.getLabel(), phase.getSortOrder(),
                status, boundedTotal, boundedDone, runnable);
    }

    // ==================================================================
    // 主流程执行（自动跳过已完成部分）
    // ==================================================================

    /**
     * 执行逆向工程（自动跳过已完成部分）。
     *
     * @param cancelled 取消判断：在每个单元（章 / 卷 / 汇总）开始前检查，
     *                  已停止时立即收尾，不再发起新的 LLM 调用
     */
    public Flux<String> runReverseEngineering(Long jobId, Long projectId, BooleanSupplier cancelled) {
        TxtImportJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId);
        Genre genre = parseGenre(job.getGenre());
        AiProviderRouter.ResolvedModel resolved = job.getModelConfigId() != null
                ? providerRouter.resolveModelByConfigId(job.getModelConfigId())
                : providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);
        int perVolume = perVolume(job);
        String title = job.getTitle();

        // 1) 扫描 → 生成计划 → 同步流程步骤表
        ReProtocol.RePlan plan = plan(job);
        syncSteps(job, plan);
        log.info("[Import:{}] 逆向工程计划: {} 项中已完成 {} 项", jobId, plan.totalUnits(), plan.completedUnits());

        // 2) 按阶段顺序执行；未启用或已完成的阶段直接跳过
        List<Flux<String>> parts = new ArrayList<>();
        parts.add(Flux.just(ReProtocol.plan(plan)));

        for (ReProtocol.PhasePlan pp : plan.phases()) {
            RePhase phase = RePhase.valueOf(pp.phase());

            if (!pp.runnable()) {
                parts.add(Flux.just(ReProtocol.phaseSkipped(phase)));
                continue;
            }
            if (pp.completedUnits() >= pp.totalUnits()) {
                parts.add(Flux.just(ReProtocol.phase(phase)));
                parts.add(Flux.just(ReProtocol.note(phase, "该阶段已完成，自动跳过")));
                parts.add(Flux.just(ReProtocol.phaseDone(phase, pp.totalUnits(), pp.totalUnits())));
                continue;
            }

            parts.add(Flux.just(ReProtocol.phase(phase)));
            parts.add(Flux.just(ReProtocol.note(phase, resumeNote(pp))));
            parts.add(Flux.defer(() -> runPhase(job, projectId, phase, chapters, title, genre,
                    resolved, perVolume, pp, cancelled)));
        }

        parts.add(Flux.defer(() -> finish(jobId, cancelled)));
        return Flux.concat(parts);
    }

    private String resumeNote(ReProtocol.PhasePlan pp) {
        if (pp.completedUnits() > 0) {
            return String.format("已完成 %d/%d，继续补做剩余 %d 项",
                    pp.completedUnits(), pp.totalUnits(), pp.totalUnits() - pp.completedUnits());
        }
        return String.format("共 %d 项待处理", pp.totalUnits());
    }

    private Flux<String> runPhase(TxtImportJobEntity job, Long projectId, RePhase phase,
                                  List<TxtImportChapterEntity> chapters, String title, Genre genre,
                                  AiProviderRouter.ResolvedModel resolved, int perVolume,
                                  ReProtocol.PhasePlan planItem, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            log.info("[Import:{}] 已停止，跳过阶段 {}", job.getId(), phase.name());
            return Flux.empty();
        }
        markStepRunning(job, phase, planItem);
        Flux<String> flux = switch (phase) {
            case CHAPTER_OUTLINE -> runChapterOutlines(job, projectId, phase, chapters, title,
                    genre, resolved, perVolume, cancelled);
            case STORY_ARC -> runStoryArcs(job, projectId, phase, chapters.size(), title,
                    genre, resolved, perVolume, cancelled);
            case WORLD -> runFinalPhase(job, projectId, phase, title, genre, resolved,
                    WorkflowStep.WORLD_BUILDING, PromptSubStep.REVERSE_FINAL_WORLD,
                    Map.of(), content -> saveWorldSetting(projectId, content), cancelled);
            case CHARACTERS -> runFinalPhase(job, projectId, phase, title, genre, resolved,
                    WorkflowStep.CHARACTER_DESIGN, PromptSubStep.REVERSE_FINAL_CHARACTERS,
                    Map.of(), content -> saveCharacters(projectId, content), cancelled);
            case STORY_OUTLINE -> runFinalPhase(job, projectId, phase, title, genre, resolved,
                    WorkflowStep.OUTLINE_GENERATION, PromptSubStep.REVERSE_FINAL_STORY_OUTLINE,
                    Map.of("totalChapters", String.valueOf(chapters.size())),
                    content -> saveStoryOutline(projectId, content), cancelled);
        };
        return flux
                .doOnComplete(() -> {
                    if (!cancelled.getAsBoolean()) {
                        markStepCompleted(job.getId(), phase, planItem.totalUnits());
                    }
                })
                .doOnError(e -> markStepFailed(job.getId(), phase, e));
    }

    // ------------------------------------------------------------------
    // 阶段 1：逐章推断【章节大纲 + 角色】
    // ------------------------------------------------------------------

    private Flux<String> runChapterOutlines(TxtImportJobEntity job, Long projectId, RePhase phase,
                                            List<TxtImportChapterEntity> chapters, String title,
                                            Genre genre, AiProviderRouter.ResolvedModel resolved,
                                            int perVolume, BooleanSupplier cancelled) {
        final int total = chapters.size();
        final String genreName = genreName(genre);

        // 一次性载入已有章节大纲 → 断点续跑依据
        Map<Integer, ChapterOutlineEntity> existingMap = new HashMap<>();
        for (ChapterOutlineEntity co : chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            existingMap.put(co.getChapterNumber(), co);
        }

        return Flux.range(0, total).concatMap(i -> Flux.defer(() -> {
            if (cancelled.getAsBoolean()) {
                return Flux.empty();
            }
            TxtImportChapterEntity ch = chapters.get(i);
            ChapterOutlineEntity existing = existingMap.get(ch.getChapterNumber());

            if (existing != null && hasText(existing.getSummary())) {
                // 已完成 → 不发 LLM，只回报进度（实时显示为「已跳过」）
                return Flux.just(
                        ReProtocol.item(new ReProtocol.ReItem(phase.name(), i + 1, total,
                                "第" + ch.getChapterNumber() + "章", ch.getTitle(),
                                existing.getSummary(), existing.getCharacterNames(), true)),
                        ReProtocol.progress(i + 1, total));
            }

            List<String> segments = splitContent(ch.getContent(), MAX_CONTEXT_CHARS);
            StringBuilder summary = new StringBuilder();
            String names = "";
            for (String seg : segments) {
                String raw = callLlm(resolved, projectId, WorkflowStep.OUTLINE_GENERATION,
                        PromptSubStep.REVERSE_CHAPTER_OUTLINE, genre, Map.of(
                                "title", safe(title),
                                "genre", genreName,
                                "chapterNumber", String.valueOf(ch.getChapterNumber()),
                                "chapterTitle", safe(ch.getTitle()),
                                "totalChapters", String.valueOf(total),
                                "chapterContent", seg
                        ), 2048, 0.4);
                String part = extractSection(raw, "大纲", List.of("角色"));
                if (part.isEmpty()) part = safe(raw).trim();
                if (summary.length() > 0) summary.append("\n");
                summary.append(part);
                names = mergeNames(names, extractSection(raw, "角色", List.of()));
            }

            ChapterOutlineEntity entity = existing != null ? existing : new ChapterOutlineEntity();
            entity.setProjectId(projectId);
            entity.setChapterNumber(ch.getChapterNumber());
            entity.setTitle(ch.getTitle());
            entity.setSummary(summary.toString().trim());
            entity.setCharacterNames(names);
            entity.setVolumeNumber(volumeNumberOf(ch.getChapterNumber(), perVolume));
            entity.setStatus("COMPLETED");
            chapterOutlineRepository.save(entity);

            String key = "第" + ch.getChapterNumber() + "章";
            ReProtocol.ReItem item = new ReProtocol.ReItem(phase.name(), i + 1, total, key,
                    ch.getTitle(), summary.toString().trim(), names, false);
            updateStepProgress(job.getId(), phase, i + 1);

            return Flux.just(ReProtocol.item(item), ReProtocol.progress(i + 1, total));
        }));
    }

    // ------------------------------------------------------------------
    // 阶段 2：分卷推断【故事弧线】
    // ------------------------------------------------------------------

    private Flux<String> runStoryArcs(TxtImportJobEntity job, Long projectId, RePhase phase,
                                      int totalChapters, String title, Genre genre,
                                      AiProviderRouter.ResolvedModel resolved, int perVolume,
                                      BooleanSupplier cancelled) {
        List<VolumeRange> volumes = computeVolumes(totalChapters, perVolume);
        final int totalVolumes = volumes.size();
        final String genreName = genreName(genre);

        Map<Integer, ChapterOutlineEntity> outlineMap = new HashMap<>();
        for (ChapterOutlineEntity co : chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            outlineMap.put(co.getChapterNumber(), co);
        }
        Map<Integer, VolumeOutlineEntity> existingVolumes = new HashMap<>();
        for (VolumeOutlineEntity vo : volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)) {
            existingVolumes.put(vo.getVolumeNumber(), vo);
        }

        return Flux.range(0, totalVolumes).concatMap(i -> Flux.defer(() -> {
            if (cancelled.getAsBoolean()) {
                return Flux.empty();
            }
            VolumeRange vol = volumes.get(i);
            String key = "第" + vol.volumeNumber() + "卷（第" + vol.chapterStart() + "-" + vol.chapterEnd() + "章）";
            VolumeOutlineEntity existing = existingVolumes.get(vol.volumeNumber());

            if (existing != null && hasText(existing.getArcSummary())) {
                return Flux.just(
                        ReProtocol.item(new ReProtocol.ReItem(phase.name(), i + 1, totalVolumes, key,
                                existing.getArcName(), existing.getArcSummary(), null, true)),
                        ReProtocol.progress(i + 1, totalVolumes));
            }

            List<String> items = new ArrayList<>();
            for (int n = vol.chapterStart(); n <= vol.chapterEnd(); n++) {
                ChapterOutlineEntity co = outlineMap.get(n);
                if (co == null) continue;
                StringBuilder sb = new StringBuilder();
                sb.append("第").append(co.getChapterNumber()).append("章");
                if (hasText(co.getTitle())) sb.append(" ").append(co.getTitle());
                sb.append("\n大纲：").append(safe(co.getSummary()));
                if (hasText(co.getCharacterNames())) sb.append("\n出场角色：").append(co.getCharacterNames());
                items.add(sb.toString());
            }
            if (items.isEmpty()) items.add("（本卷暂无章节大纲）");

            List<List<String>> groups = chunkItems(items, MAX_CONTEXT_CHARS);

            String arcName = "";
            StringBuilder arcSummary = new StringBuilder();
            for (List<String> group : groups) {
                String raw = callLlm(resolved, projectId, WorkflowStep.OUTLINE_GENERATION,
                        PromptSubStep.REVERSE_STORY_ARC, genre, Map.of(
                                "title", safe(title),
                                "genre", genreName,
                                "volumeNumber", String.valueOf(vol.volumeNumber()),
                                "totalVolumes", String.valueOf(totalVolumes),
                                "chapterStart", String.valueOf(vol.chapterStart()),
                                "chapterEnd", String.valueOf(vol.chapterEnd()),
                                "chapterOutlines", String.join("\n\n", group)
                        ), 4096, 0.5);
                String name = extractSection(raw, "弧线名", List.of("弧线概要"));
                if (!name.isEmpty() && arcName.isEmpty()) arcName = name;
                String sum = extractSection(raw, "弧线概要", List.of());
                if (sum.isEmpty()) sum = safe(raw).trim();
                if (arcSummary.length() > 0) arcSummary.append("\n\n");
                arcSummary.append(sum);
            }

            VolumeOutlineEntity vo = existing != null ? existing : new VolumeOutlineEntity();
            vo.setProjectId(projectId);
            vo.setVolumeNumber(vol.volumeNumber());
            vo.setTitle(arcName.isBlank() ? key : arcName);
            vo.setArcName(arcName);
            vo.setArcSummary(arcSummary.toString().trim());
            vo.setChapterStart(vol.chapterStart());
            vo.setChapterEnd(vol.chapterEnd());
            volumeOutlineRepository.save(vo);

            ReProtocol.ReItem item = new ReProtocol.ReItem(phase.name(), i + 1, totalVolumes, key,
                    arcName, arcSummary.toString().trim(), null, false);
            updateStepProgress(job.getId(), phase, i + 1);

            return Flux.just(ReProtocol.item(item), ReProtocol.progress(i + 1, totalVolumes));
        }));
    }

    // ------------------------------------------------------------------
    // 阶段 3：由故事弧线汇总【世界观 / 角色 / 故事总纲】
    // ------------------------------------------------------------------

    private Flux<String> runFinalPhase(TxtImportJobEntity job, Long projectId, RePhase phase,
                                       String title, Genre genre,
                                       AiProviderRouter.ResolvedModel resolved,
                                       WorkflowStep step, PromptSubStep subStep,
                                       Map<String, String> extraVars,
                                       Consumer<String> saver, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return Flux.empty();
        }
        List<String> blocks = volumeBlocks(projectId);
        List<List<String>> groups = chunkItems(blocks, MAX_CONTEXT_CHARS);
        final String genreName = genreName(genre);

        if (groups.size() <= 1) {
            // 上下文未超限：流式输出，前端可实时看到生成过程
            Map<String, String> vars = new HashMap<>();
            vars.put("title", safe(title));
            vars.put("genre", genreName);
            vars.putAll(extraVars);
            vars.put("arcsInfo", blocks.isEmpty() ? "（暂无故事弧线）" : String.join("\n\n", blocks));

            String userPrompt = promptRegistry.resolveTemplate(
                    promptRegistry.getSubStepTemplate(step, subStep, genre), vars);
            String systemPrompt = promptRegistry.getSubStepSystemPrompt(step, subStep, genre);

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
                        saver.accept(content.toString());
                        updateStepProgress(job.getId(), phase, 1);
                        log.info("[Import:{}] {} completed", job.getId(), phase.name());
                    });
        }

        // 上下文超限：拆分多个小节分别调用（非流式），再按卷序合并
        return Flux.defer(() -> {
            StringBuilder merged = new StringBuilder();
            for (int gi = 0; gi < groups.size(); gi++) {
                if (cancelled.getAsBoolean()) {
                    return Flux.empty();
                }
                Map<String, String> vars = new HashMap<>();
                vars.put("title", safe(title));
                vars.put("genre", genreName);
                vars.putAll(extraVars);
                vars.put("arcsInfo", String.join("\n\n", groups.get(gi)));

                String raw = callLlm(resolved, projectId, step, subStep, genre, vars, 8192, 0.5);
                String part = safe(raw).trim();
                if (merged.length() > 0) merged.append("\n\n");
                merged.append(part);
                log.info("[Import:{}] {} chunk {}/{} done", job.getId(), phase.name(), gi + 1, groups.size());
            }
            String result = merged.toString().trim();
            saver.accept(result);
            updateStepProgress(job.getId(), phase, 1);
            ReProtocol.ReItem item = new ReProtocol.ReItem(phase.name(), 1, 1, phase.getLabel(),
                    phase.getLabel(), result, null, false);
            return Flux.just(ReProtocol.item(item), ReProtocol.progress(1, 1));
        });
    }

    // ==================================================================
    // 流程步骤表维护
    // ==================================================================

    private void syncSteps(TxtImportJobEntity job, ReProtocol.RePlan plan) {
        for (ReProtocol.PhasePlan pp : plan.phases()) {
            TxtImportReStepEntity step = reStepRepository
                    .findByJobIdAndPhase(job.getId(), pp.phase())
                    .orElseGet(() -> {
                        TxtImportReStepEntity s = new TxtImportReStepEntity();
                        s.setJobId(job.getId());
                        s.setPhase(pp.phase());
                        return s;
                    });
            step.setSortOrder(pp.sortOrder());
            step.setTotalUnits(pp.totalUnits());
            step.setCompletedUnits(pp.completedUnits());
            step.setStatus(pp.status());
            step.setErrorMessage(null);
            reStepRepository.save(step);
        }
    }

    private void markStepRunning(TxtImportJobEntity job, RePhase phase, ReProtocol.PhasePlan planItem) {
        job.setStatus("RE_" + phase.name());
        job.setProgressNote(phase.getLabel() + "（" + planItem.completedUnits() + "/" + planItem.totalUnits() + "）");
        jobRepository.save(job);

        TxtImportReStepEntity step = reStepRepository.findByJobIdAndPhase(job.getId(), phase.name())
                .orElseGet(() -> {
                    TxtImportReStepEntity s = new TxtImportReStepEntity();
                    s.setJobId(job.getId());
                    s.setPhase(phase.name());
                    return s;
                });
        step.setSortOrder(phase.getSortOrder());
        step.setTotalUnits(planItem.totalUnits());
        step.setCompletedUnits(planItem.completedUnits());
        step.setStatus(RePhase.Status.RUNNING.name());
        step.setStartedAt(LocalDateTime.now());
        step.setFinishedAt(null);
        step.setErrorMessage(null);
        reStepRepository.save(step);
    }

    private void updateStepProgress(Long jobId, RePhase phase, int completed) {
        reStepRepository.findByJobIdAndPhase(jobId, phase.name()).ifPresent(step -> {
            if (step.getCompletedUnits() != completed) {
                step.setCompletedUnits(completed);
                reStepRepository.save(step);
            }
        });
    }

    private void markStepCompleted(Long jobId, RePhase phase, int total) {
        reStepRepository.findByJobIdAndPhase(jobId, phase.name()).ifPresent(step -> {
            step.setStatus(RePhase.Status.COMPLETED.name());
            step.setCompletedUnits(total);
            step.setFinishedAt(LocalDateTime.now());
            step.setErrorMessage(null);
            reStepRepository.save(step);
        });
        log.info("[Import:{}] phase {} completed ({} units)", jobId, phase.name(), total);
    }

    private void markStepFailed(Long jobId, RePhase phase, Throwable e) {
        String msg = e == null ? "未知错误" : String.valueOf(e.getMessage());
        reStepRepository.findByJobIdAndPhase(jobId, phase.name()).ifPresent(step -> {
            step.setStatus(RePhase.Status.FAILED.name());
            step.setErrorMessage(msg.length() > 990 ? msg.substring(0, 990) : msg);
            reStepRepository.save(step);
        });
        jobRepository.findById(jobId).ifPresent(j -> {
            j.setStatus("FAILED");
            j.setErrorMessage(phase.getLabel() + "失败: " + msg);
            jobRepository.save(j);
        });
    }

    /**
     * 清空该任务的逆向工程产出，使流程回到「未开始」。
     * <p>用于用户改了每卷章节数或模型后想整体重跑的场景。
     */
    @Transactional
    public void resetProgress(Long jobId) {
        TxtImportJobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        Long projectId = job.getProjectId();
        if (projectId != null) {
            chapterOutlineRepository.deleteByProjectId(projectId);
            volumeOutlineRepository.deleteByProjectId(projectId);
            worldSettingRepository.findByProjectId(projectId).ifPresent(worldSettingRepository::delete);
            storyOutlineRepository.findByProjectId(projectId).ifPresent(storyOutlineRepository::delete);
            characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                    .filter(c -> REVERSE_CHARACTER_NAME.equals(c.getName()))
                    .forEach(characterRepository::delete);
        }
        for (TxtImportReStepEntity s : reStepRepository.findByJobIdOrderBySortOrder(jobId)) {
            s.setStatus(RePhase.Status.PENDING.name());
            s.setCompletedUnits(0);
            s.setStartedAt(null);
            s.setFinishedAt(null);
            s.setErrorMessage(null);
            reStepRepository.save(s);
        }
        job.setStatus("SPLIT_DONE");
        job.setProgressNote("已清空逆向工程进度");
        jobRepository.save(job);
        log.info("[Import:{}] 已清空逆向工程进度", jobId);
    }

    /** 手动停止：复位进行中的步骤，任务标记为可继续。 */
    @Transactional
    public void markStopped(Long jobId) {
        int reset = reStepRepository.resetRunningToPending(jobId);
        jobRepository.findById(jobId).ifPresent(j -> {
            j.setStatus("INTERRUPTED");
            j.setProgressNote("已停止，可继续执行剩余部分");
            jobRepository.save(j);
        });
        log.info("[Import:{}] 已停止逆向工程，复位 {} 个进行中步骤", jobId, reset);
    }

    private Flux<String> finish(Long jobId, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            log.info("[Import:{}] reverse engineering stopped by user", jobId);
            return Flux.empty();
        }
        jobRepository.findById(jobId).ifPresent(j -> {
            j.setStatus("DONE");
            j.setProgressNote("逆向工程完成");
            jobRepository.save(j);
        });
        log.info("[Import:{}] reverse engineering finished", jobId);
        return Flux.empty();
    }

    // ==================================================================
    // 落库
    // ==================================================================

    private void saveWorldSetting(Long projectId, String content) {
        WorldSettingEntity ws = worldSettingRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    WorldSettingEntity entity = new WorldSettingEntity();
                    entity.setProjectId(projectId);
                    return entity;
                });
        ws.setContent(content);
        worldSettingRepository.save(ws);
    }

    private void saveCharacters(Long projectId, String content) {
        characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .filter(c -> REVERSE_CHARACTER_NAME.equals(c.getName()))
                .forEach(characterRepository::delete);
        CharacterEntity character = new CharacterEntity();
        character.setProjectId(projectId);
        character.setName(REVERSE_CHARACTER_NAME);
        character.setContent(content);
        character.setStatus("GENERATED");
        character.setSortOrder(0);
        characterRepository.save(character);
    }

    private void saveStoryOutline(Long projectId, String content) {
        StoryOutlineEntity outline = storyOutlineRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    StoryOutlineEntity entity = new StoryOutlineEntity();
                    entity.setProjectId(projectId);
                    return entity;
                });
        outline.setContent(content);
        storyOutlineRepository.save(outline);
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /** 阻塞式调用（用于拆分后的小节调用）。 */
    private String callLlm(AiProviderRouter.ResolvedModel resolved, Long projectId,
                           WorkflowStep step, PromptSubStep subStep, Genre genre,
                           Map<String, String> vars, int maxTokens, double temperature) {
        String userPrompt = promptRegistry.resolveTemplate(
                promptRegistry.getSubStepTemplate(step, subStep, genre), vars);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(step, subStep, genre);

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        applyResolvedConfig(request, resolved);

        long startTime = System.currentTimeMillis();
        String out = resolved.provider().generateText(request);
        aiUsageTracker.record(projectId, resolved.modelId(),
                resolved.provider().getProviderName(),
                System.currentTimeMillis() - startTime);
        return out == null ? "" : out;
    }

    private List<String> volumeBlocks(Long projectId) {
        List<String> blocks = new ArrayList<>();
        for (VolumeOutlineEntity v : volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)) {
            StringBuilder sb = new StringBuilder();
            sb.append("第").append(v.getVolumeNumber()).append("卷（第")
                    .append(v.getChapterStart()).append("-").append(v.getChapterEnd()).append("章）");
            if (hasText(v.getArcName())) sb.append(" 弧线名：").append(v.getArcName());
            sb.append("\n").append(safe(v.getArcSummary()));
            blocks.add(sb.toString());
        }
        return blocks;
    }

    /** 把条目按累计字符数切成若干组，保证单组上下文不超过 maxChars。 */
    private List<List<String>> chunkItems(List<String> items, int maxChars) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int len = 0;
        for (String item : items) {
            if (!current.isEmpty() && len + item.length() > maxChars) {
                groups.add(current);
                current = new ArrayList<>();
                len = 0;
            }
            current.add(item);
            len += item.length();
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    /** 超长单章按段落切分为多个小节。 */
    private List<String> splitContent(String content, int maxChars) {
        if (!hasText(content)) return List.of("");
        if (content.length() <= maxChars) return List.of(content);

        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : content.split("\n")) {
            if (current.length() > 0 && current.length() + para.length() + 1 > maxChars) {
                segments.add(current.toString());
                current.setLength(0);
            }
            current.append(para).append("\n");
        }
        if (current.length() > 0) segments.add(current.toString());
        return segments.isEmpty() ? List.of(content) : segments;
    }

    /** 抽取形如 {@code ===标签===} 到下一个 {@code ===其它标签===} 之间的内容。 */
    private String extractSection(String text, String tag, List<String> endTags) {
        if (!hasText(text)) return "";
        Matcher m = Pattern.compile("={2,}\\s*" + tag + "\\s*={2,}").matcher(text);
        if (!m.find()) return "";
        int start = m.end();
        int end = text.length();
        for (String endTag : endTags) {
            Matcher em = Pattern.compile("={2,}\\s*" + endTag + "\\s*={2,}").matcher(text);
            if (em.find(start)) {
                end = em.start();
                break;
            }
        }
        return text.substring(start, end).trim();
    }

    /** 角色名按「、」合并去重，保持出现顺序。 */
    private String mergeNames(String a, String b) {
        LinkedHashSet<String> set = new LinkedHashSet<>(splitNames(a));
        set.addAll(splitNames(b));
        return String.join("、", set);
    }

    private List<String> splitNames(String s) {
        if (!hasText(s)) return List.of();
        return Arrays.stream(s.split("[、,，;；/|\\s\\u3000]+"))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    private List<VolumeRange> computeVolumes(int totalChapters, int volumeSize) {
        if (volumeSize <= 0) volumeSize = DEFAULT_CHAPTERS_PER_VOLUME;
        List<VolumeRange> volumes = new ArrayList<>();
        int vol = 1;
        for (int start = 1; start <= totalChapters; start += volumeSize) {
            int end = Math.min(start + volumeSize - 1, totalChapters);
            volumes.add(new VolumeRange(vol++, start, end));
        }
        return volumes;
    }

    private int volumeNumberOf(int chapterNumber, int perVolume) {
        if (perVolume <= 0) perVolume = DEFAULT_CHAPTERS_PER_VOLUME;
        return (chapterNumber - 1) / perVolume + 1;
    }

    private int perVolume(TxtImportJobEntity job) {
        int v = job.getChaptersPerVolume();
        return v > 0 ? v : DEFAULT_CHAPTERS_PER_VOLUME;
    }

    private Genre parseGenre(String raw) {
        if (!hasText(raw)) return null;
        try {
            return Genre.valueOf(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String genreName(Genre genre) {
        return genre != null ? genre.getDisplayName() : "未指定";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
