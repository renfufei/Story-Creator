package com.storycreator.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import com.storycreator.workflow.autorun.AutoRunStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImportService {

    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final StepModelConfigRepository stepModelConfigRepository;
    private final AiModelConfigRepository aiModelConfigRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;

    public ImportService(ObjectMapper objectMapper,
                        ProjectRepository projectRepository,
                        WorldSettingRepository worldSettingRepository,
                        CharacterRepository characterRepository,
                        StoryOutlineRepository storyOutlineRepository,
                        VolumeOutlineRepository volumeOutlineRepository,
                        ChapterOutlineRepository chapterOutlineRepository,
                        ChapterRepository chapterRepository,
                        WorkflowStateRepository workflowStateRepository,
                        StepGuidanceRepository stepGuidanceRepository,
                        StepModelConfigRepository stepModelConfigRepository,
                        AiModelConfigRepository aiModelConfigRepository,
                        ProofreadingReportRepository proofreadingReportRepository) {
        this.objectMapper = objectMapper;
        this.projectRepository = projectRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.stepModelConfigRepository = stepModelConfigRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
    }

    public ProjectJsonDto parseJson(byte[] data) {
        try {
            var dto = objectMapper.readValue(data, ProjectJsonDto.class);
            if (dto.version() != 1) {
                throw new IllegalArgumentException("不支持的版本: " + dto.version());
            }
            if (dto.project() == null) {
                throw new IllegalArgumentException("无效的导入文件：缺少项目数据");
            }
            return dto;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Long importProject(ProjectJsonDto dto, String overrideName, boolean overwrite) {
        String title = (overrideName != null && !overrideName.isBlank())
                ? overrideName.trim()
                : dto.project().title();

        if (overwrite) {
            projectRepository.findByTitle(title).ifPresent(existing ->
                    deleteAllProjectData(existing.getId()));
        }

        // Create project
        var project = new ProjectEntity();
        project.setTitle(title);
        project.setGenre(Genre.valueOf(dto.project().genre()));
        project.setDescription(dto.project().description());
        project.setCurrentStep(WorkflowStep.valueOf(dto.project().currentStep()));
        project.setTotalChapters(dto.project().totalChapters());
        project.setChapterWordCount(dto.project().chapterWordCount());
        project.setChapterWordCountMin(dto.project().chapterWordCountMin());
        project.setChapterWordCountMax(dto.project().chapterWordCountMax());
        project.setCharacterCount(dto.project().characterCount());
        project.setChaptersPerVolume(dto.project().chaptersPerVolume() > 0 ? dto.project().chaptersPerVolume() : 10);
        project.setAutoMode(dto.project().autoMode());
        project.setAutoRunStatus(AutoRunStatus.IDLE);
        project = projectRepository.save(project);
        Long projectId = project.getId();

        // World setting
        if (dto.worldSetting() != null) {
            var ws = new WorldSettingEntity();
            ws.setProjectId(projectId);
            ws.setContent(dto.worldSetting().content());
            ws.setSummary(dto.worldSetting().summary());
            worldSettingRepository.save(ws);
        }

        // Characters
        if (dto.characters() != null) {
            for (var cd : dto.characters()) {
                var c = new CharacterEntity();
                c.setProjectId(projectId);
                c.setName(cd.name());
                c.setRole(cd.role());
                c.setGender(cd.gender());
                c.setPersonality(cd.personality());
                c.setAppearance(cd.appearance());
                c.setBackground(cd.background());
                c.setMotivation(cd.motivation());
                c.setRelationships(cd.relationships());
                c.setAbilities(cd.abilities());
                c.setAge(cd.age());
                c.setDescription(cd.description());
                c.setContent(cd.content());
                c.setSummary(cd.summary());
                c.setSortOrder(cd.sortOrder());
                characterRepository.save(c);
            }
        }

        // Story outline
        if (dto.storyOutline() != null) {
            var so = new StoryOutlineEntity();
            so.setProjectId(projectId);
            so.setContent(dto.storyOutline().content());
            so.setTotalChapters(dto.storyOutline().totalChapters());
            storyOutlineRepository.save(so);
        }

        // Volume outlines
        if (dto.volumeOutlines() != null) {
            for (var vd : dto.volumeOutlines()) {
                var v = new VolumeOutlineEntity();
                v.setProjectId(projectId);
                v.setVolumeNumber(vd.volumeNumber());
                v.setTitle(vd.title());
                v.setArcSummary(vd.arcSummary());
                v.setChapterStart(vd.chapterStart());
                v.setChapterEnd(vd.chapterEnd());
                volumeOutlineRepository.save(v);
            }
        }

        // Chapter outlines
        if (dto.chapterOutlines() != null) {
            for (var cod : dto.chapterOutlines()) {
                var co = new ChapterOutlineEntity();
                co.setProjectId(projectId);
                co.setChapterNumber(cod.chapterNumber());
                co.setTitle(cod.title());
                co.setSummary(cod.summary());
                co.setCharacterNames(cod.characterNames());
                co.setVolumeNumber(cod.volumeNumber());
                co.setStatus(cod.status());
                chapterOutlineRepository.save(co);
            }
        }

        // Chapters
        if (dto.chapters() != null) {
            for (var chd : dto.chapters()) {
                var ch = new ChapterEntity();
                ch.setProjectId(projectId);
                ch.setChapterNumber(chd.chapterNumber());
                ch.setTitle(chd.title());
                ch.setContent(chd.content());
                ch.setContentDraft(chd.contentDraft());
                ch.setWordCount(chd.wordCount());
                ch.setStatus(StepStatus.valueOf(chd.status()));
                ch.setPolishNote(chd.polishNote());
                ch.setPolishStatus(StepStatus.valueOf(chd.polishStatus()));
                ch.setProofreadStatus(StepStatus.valueOf(chd.proofreadStatus()));
                ch.setPlotSummary(chd.plotSummary());
                chapterRepository.save(ch);
            }
        }

        // Workflow states
        if (dto.workflowStates() != null) {
            for (var wsd : dto.workflowStates()) {
                var ws = new WorkflowStateEntity();
                ws.setProjectId(projectId);
                ws.setStep(WorkflowStep.valueOf(wsd.step()));
                ws.setStatus(StepStatus.valueOf(wsd.status()));
                ws.setGeneratedContent(wsd.generatedContent());
                ws.setUserEditedContent(wsd.userEditedContent());
                workflowStateRepository.save(ws);
            }
        }

        // Step guidances
        if (dto.stepGuidances() != null) {
            for (var sgd : dto.stepGuidances()) {
                var sg = new StepGuidanceEntity();
                sg.setProjectId(projectId);
                sg.setStep(WorkflowStep.valueOf(sgd.step()));
                sg.setGuidance(sgd.guidance());
                stepGuidanceRepository.save(sg);
            }
        }

        // Step model configs
        if (dto.stepModelConfigs() != null && !dto.stepModelConfigs().isEmpty()) {
            // Build lookup: "provider:modelId" -> configId for active TEXT configs
            Map<String, Long> codeToConfigId = aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)
                    .stream()
                    .collect(Collectors.toMap(
                            c -> c.getProvider() + ":" + c.getModelId(),
                            AiModelConfigEntity::getId,
                            (a, b) -> a));
            for (var smcd : dto.stepModelConfigs()) {
                Long configId = codeToConfigId.get(smcd.modelCode());
                if (configId != null) {
                    var smc = new StepModelConfigEntity();
                    smc.setProjectId(projectId);
                    smc.setStep(WorkflowStep.valueOf(smcd.step()));
                    smc.setModelConfigId(configId);
                    stepModelConfigRepository.save(smc);
                }
            }
        }

        // Proofreading reports
        if (dto.proofreadingReports() != null) {
            for (var prd : dto.proofreadingReports()) {
                var pr = new ProofreadingReportEntity();
                pr.setProjectId(projectId);
                pr.setChapterNumber(prd.chapterNumber());
                pr.setPlotSummary(prd.plotSummary());
                pr.setCharacterIssues(prd.characterIssues());
                pr.setConsistencyIssues(prd.consistencyIssues());
                pr.setContinuityIssues(prd.continuityIssues());
                pr.setForeshadowing(prd.foreshadowing());
                proofreadingReportRepository.save(pr);
            }
        }

        return projectId;
    }

    @Transactional
    public void deleteAllProjectData(Long projectId) {
        proofreadingReportRepository.deleteByProjectId(projectId);
        stepModelConfigRepository.deleteByProjectId(projectId);
        stepGuidanceRepository.deleteByProjectId(projectId);
        workflowStateRepository.deleteByProjectId(projectId);
        chapterRepository.deleteByProjectId(projectId);
        chapterOutlineRepository.deleteByProjectId(projectId);
        volumeOutlineRepository.deleteByProjectId(projectId);
        storyOutlineRepository.deleteByProjectId(projectId);
        characterRepository.deleteByProjectId(projectId);
        worldSettingRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
    }
}
