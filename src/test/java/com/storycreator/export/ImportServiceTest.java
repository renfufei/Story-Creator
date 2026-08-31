package com.storycreator.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ModelType;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private StoryOutlineRepository storyOutlineRepository;
    @Mock private VolumeOutlineRepository volumeOutlineRepository;
    @Mock private ChapterOutlineRepository chapterOutlineRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private WorkflowStateRepository workflowStateRepository;
    @Mock private StepGuidanceRepository stepGuidanceRepository;
    @Mock private StepModelConfigRepository stepModelConfigRepository;
    @Mock private AiModelConfigRepository aiModelConfigRepository;
    @Mock private ProofreadingReportRepository proofreadingReportRepository;
    @Mock private WorldSettingFacetRepository worldSettingFacetRepository;

    private ImportService importService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        importService = new ImportService(objectMapper, projectRepository, worldSettingRepository,
                characterRepository, storyOutlineRepository, volumeOutlineRepository,
                chapterOutlineRepository, chapterRepository, workflowStateRepository,
                stepGuidanceRepository, stepModelConfigRepository, aiModelConfigRepository,
                proofreadingReportRepository, worldSettingFacetRepository);
    }

    // ==================== parseJson ====================

    @Test
    void parseJson_validVersion1_returnsDto() throws Exception {
        String json = """
                {"version":1,"project":{"title":"测试","genre":"QIHUAN","description":null,
                "currentStep":"WORLD_BUILDING","totalChapters":10,"chapterWordCount":2000,
                "chapterWordCountMin":0,"chapterWordCountMax":0,"characterCount":3,
                "chaptersPerVolume":10,"autoMode":false},
                "worldSetting":null,"characters":[],"storyOutline":null,
                "volumeOutlines":[],"chapterOutlines":[],"chapters":[],
                "workflowStates":[],"stepGuidances":[],"stepModelConfigs":[],
                "proofreadingReports":[]}
                """;

        ProjectJsonDto dto = importService.parseJson(json.getBytes());

        assertEquals(1, dto.version());
        assertEquals("测试", dto.project().title());
    }

    @Test
    void parseJson_unsupportedVersion_throwsIllegalArgument() throws Exception {
        String json = """
                {"version":99,"project":{"title":"x","genre":"QIHUAN","description":null,
                "currentStep":"WORLD_BUILDING","totalChapters":1,"chapterWordCount":1000,
                "chapterWordCountMin":0,"chapterWordCountMax":0,"characterCount":1,
                "chaptersPerVolume":10,"autoMode":false}}
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importService.parseJson(json.getBytes()));
        assertTrue(ex.getMessage().contains("不支持的版本"));
    }

    @Test
    void parseJson_missingProject_throwsIllegalArgument() throws Exception {
        String json = """
                {"version":1,"project":null,"worldSetting":null,"characters":null,
                "storyOutline":null,"volumeOutlines":null,"chapterOutlines":null,
                "chapters":null,"workflowStates":null,"stepGuidances":null,
                "stepModelConfigs":null,"proofreadingReports":null}
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importService.parseJson(json.getBytes()));
        assertTrue(ex.getMessage().contains("缺少项目数据"));
    }

    @Test
    void parseJson_invalidJson_throwsIllegalArgument() {
        byte[] badJson = "not-json".getBytes();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importService.parseJson(badJson));
        assertTrue(ex.getMessage().startsWith("JSON 解析失败"));
    }

    // ==================== importProject ====================

    @Test
    void importProject_createsProjectWithCorrectFields() {
        var dto = buildMinimalDto("新小说", "QIHUAN");
        ProjectEntity saved = new ProjectEntity();
        saved.setId(100L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        Long id = importService.importProject(dto, null, false);

        assertEquals(100L, id);
        verify(projectRepository).save(argThat(p -> "新小说".equals(p.getTitle())
                && p.getGenre() == Genre.QIHUAN));
    }

    @Test
    void importProject_overrideNameTakesPrecedence() {
        var dto = buildMinimalDto("原始名称", "QIHUAN");
        ProjectEntity saved = new ProjectEntity();
        saved.setId(200L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, "覆盖名称", false);

        verify(projectRepository).save(argThat(p -> "覆盖名称".equals(p.getTitle())));
    }

    @Test
    void importProject_overwriteMode_deletesExistingProject() {
        var dto = buildMinimalDto("已有项目", "QIHUAN");
        ProjectEntity existing = new ProjectEntity();
        existing.setId(50L);
        when(projectRepository.findByTitle("已有项目")).thenReturn(Optional.of(existing));

        ProjectEntity saved = new ProjectEntity();
        saved.setId(51L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, true);

        // deleteAllProjectData cascade calls
        verify(projectRepository).deleteById(50L);
        verify(chapterRepository).deleteByProjectId(50L);
        verify(worldSettingRepository).deleteByProjectId(50L);
    }

    @Test
    void importProject_noOverwrite_doesNotLookupExisting() {
        var dto = buildMinimalDto("新项目", "QIHUAN");
        ProjectEntity saved = new ProjectEntity();
        saved.setId(60L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, false);

        verify(projectRepository, never()).findByTitle(any());
    }

    @Test
    void importProject_worldSettingRestored() {
        var worldSetting = new ProjectJsonDto.WorldSettingData("世界观内容", "摘要", null);
        var dto = buildDtoWithWorldSetting(worldSetting);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(70L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, false);

        verify(worldSettingRepository).save(argThat(ws -> "世界观内容".equals(ws.getContent())));
    }

    @Test
    void importProject_unknownFacetKeySkipped() {
        var facets = Map.of("UNKNOWN_KEY", "内容");
        var worldSetting = new ProjectJsonDto.WorldSettingData("ws", null, facets);
        var dto = buildDtoWithWorldSetting(worldSetting);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(80L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        // Should not throw
        assertDoesNotThrow(() -> importService.importProject(dto, null, false));
        // Unknown facet should not be saved
        verify(worldSettingFacetRepository, never()).save(any());
    }

    @Test
    void importProject_knownFacetKeySaved() {
        var facets = Map.of("POWER_SYSTEM", "力量体系内容");
        var worldSetting = new ProjectJsonDto.WorldSettingData("ws", null, facets);
        var dto = buildDtoWithWorldSetting(worldSetting);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(90L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, false);

        verify(worldSettingFacetRepository).save(argThat(f -> "力量体系内容".equals(f.getContent())));
    }

    @Test
    void importProject_stepModelConfigMatchedByProviderAndModelId() {
        var smcData = new ProjectJsonDto.StepModelConfigData("CHAPTER_WRITING", "openai:gpt-4o");
        var dto = buildDtoWithStepModelConfig(smcData);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(110L);
        when(projectRepository.save(any())).thenReturn(saved);

        AiModelConfigEntity config = new AiModelConfigEntity();
        config.setId(77L);
        config.setProvider("openai");
        config.setModelId("gpt-4o");
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of(config));

        importService.importProject(dto, null, false);

        verify(stepModelConfigRepository).save(argThat(smc ->
                smc.getModelConfigId().equals(77L)
                && smc.getStep() == WorkflowStep.CHAPTER_WRITING));
    }

    @Test
    void importProject_stepModelConfigSkippedWhenProviderNotFound() {
        var smcData = new ProjectJsonDto.StepModelConfigData("CHAPTER_WRITING", "openai:gpt-4o");
        var dto = buildDtoWithStepModelConfig(smcData);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(120L);
        when(projectRepository.save(any())).thenReturn(saved);
        // No matching config
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, false);

        verify(stepModelConfigRepository, never()).save(any());
    }

    @Test
    void importProject_chaptersPerVolumeDefaultsTo10WhenZero() {
        // chaptersPerVolume=0 should be replaced with 10
        var projectData = new ProjectJsonDto.ProjectData(
                "标题", "QIHUAN", null, "WORLD_BUILDING",
                10, 2000, 0, 0, 3, 0, false);
        var dto = new ProjectJsonDto(1, projectData, null, null, null,
                null, null, null, null, null, List.of(), null);

        ProjectEntity saved = new ProjectEntity();
        saved.setId(130L);
        when(projectRepository.save(any())).thenReturn(saved);
        when(aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT)).thenReturn(List.of());

        importService.importProject(dto, null, false);

        verify(projectRepository).save(argThat(p -> p.getChaptersPerVolume() == 10));
    }

    // ==================== deleteAllProjectData ====================

    @Test
    void deleteAllProjectData_callsAllRepositoriesInOrder() {
        importService.deleteAllProjectData(5L);

        // verify all delete calls happened
        verify(proofreadingReportRepository).deleteByProjectId(5L);
        verify(stepModelConfigRepository).deleteByProjectId(5L);
        verify(stepGuidanceRepository).deleteByProjectId(5L);
        verify(workflowStateRepository).deleteByProjectId(5L);
        verify(chapterRepository).deleteByProjectId(5L);
        verify(chapterOutlineRepository).deleteByProjectId(5L);
        verify(volumeOutlineRepository).deleteByProjectId(5L);
        verify(storyOutlineRepository).deleteByProjectId(5L);
        verify(characterRepository).deleteByProjectId(5L);
        verify(worldSettingFacetRepository).deleteByProjectId(5L);
        verify(worldSettingRepository).deleteByProjectId(5L);
        verify(projectRepository).deleteById(5L);
    }

    // ==================== Helpers ====================

    private ProjectJsonDto buildMinimalDto(String title, String genre) {
        var projectData = new ProjectJsonDto.ProjectData(
                title, genre, null, "WORLD_BUILDING",
                10, 2000, 0, 0, 3, 10, false);
        return new ProjectJsonDto(1, projectData, null, null, null,
                null, null, null, null, null, List.of(), null);
    }

    private ProjectJsonDto buildDtoWithWorldSetting(ProjectJsonDto.WorldSettingData worldSetting) {
        var projectData = new ProjectJsonDto.ProjectData(
                "项目", "QIHUAN", null, "WORLD_BUILDING",
                10, 2000, 0, 0, 3, 10, false);
        return new ProjectJsonDto(1, projectData, worldSetting, null, null,
                null, null, null, null, null, List.of(), null);
    }

    private ProjectJsonDto buildDtoWithStepModelConfig(ProjectJsonDto.StepModelConfigData smcData) {
        var projectData = new ProjectJsonDto.ProjectData(
                "项目", "QIHUAN", null, "WORLD_BUILDING",
                10, 2000, 0, 0, 3, 10, false);
        return new ProjectJsonDto(1, projectData, null, null, null,
                null, null, null, null, null, List.of(smcData), null);
    }
}
