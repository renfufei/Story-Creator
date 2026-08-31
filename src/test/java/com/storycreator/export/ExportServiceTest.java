package com.storycreator.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private StoryOutlineRepository storyOutlineRepository;
    @Mock private ChapterRepository chapterRepository;
    @Mock private VolumeOutlineRepository volumeOutlineRepository;
    @Mock private ChapterOutlineRepository chapterOutlineRepository;
    @Mock private WorkflowStateRepository workflowStateRepository;
    @Mock private StepGuidanceRepository stepGuidanceRepository;
    @Mock private StepModelConfigRepository stepModelConfigRepository;
    @Mock private AiModelConfigRepository aiModelConfigRepository;
    @Mock private ProofreadingReportRepository proofreadingReportRepository;
    @Mock private SideStoryRepository sideStoryRepository;
    @Mock private SideStoryChapterRepository sideStoryChapterRepository;
    @Mock private WorldSettingFacetRepository worldSettingFacetRepository;

    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(
                projectRepository, worldSettingRepository, characterRepository,
                storyOutlineRepository, chapterRepository, volumeOutlineRepository,
                chapterOutlineRepository, workflowStateRepository, stepGuidanceRepository,
                stepModelConfigRepository, aiModelConfigRepository, proofreadingReportRepository,
                sideStoryRepository, sideStoryChapterRepository, worldSettingFacetRepository,
                new ObjectMapper());
    }

    private ProjectEntity makeProject() {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setTitle("测试小说");
        p.setGenre(Genre.QIHUAN);
        return p;
    }

    // ==================== exportMarkdown ====================

    @Test
    void exportMarkdown_containsTitleAndGenre() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportMarkdown(1L);

        assertTrue(result.contains("# 测试小说"));
        assertTrue(result.contains("奇幻"));
    }

    @Test
    void exportMarkdown_includesWorldSettingWhenPresent() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        WorldSettingEntity ws = new WorldSettingEntity();
        ws.setContent("这是世界观");
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.of(ws));
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportMarkdown(1L);

        assertTrue(result.contains("## 世界观设定"));
        assertTrue(result.contains("这是世界观"));
    }

    @Test
    void exportMarkdown_includesChaptersWithTitles() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());

        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(1);
        ch.setTitle("起点");
        ch.setContent("正文内容");
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of(ch));
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportMarkdown(1L);

        assertTrue(result.contains("### 第1章 起点"));
        assertTrue(result.contains("正文内容"));
    }

    @Test
    void exportMarkdown_includesChapterWithoutTitle() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());

        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(2);
        ch.setTitle(null);
        ch.setContent("内容");
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of(ch));
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportMarkdown(1L);

        assertTrue(result.contains("### 第2章\n"));
    }

    @Test
    void exportMarkdown_includesSideStoriesSection() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        SideStoryEntity ss = new SideStoryEntity();
        ss.setId(10L);
        ss.setTitle("番外一");
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of(ss));

        SideStoryChapterEntity ssCh = new SideStoryChapterEntity();
        ssCh.setChapterNumber(1);
        ssCh.setTitle("番外章节");
        ssCh.setContent("番外内容");
        when(sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(10L)).thenReturn(List.of(ssCh));

        String result = exportService.exportMarkdown(1L);

        assertTrue(result.contains("## 番外"));
        assertTrue(result.contains("### 番外一"));
        assertTrue(result.contains("#### 第1章 番外章节"));
        assertTrue(result.contains("番外内容"));
    }

    @Test
    void exportMarkdown_noSideStoriesSectionWhenEmpty() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportMarkdown(1L);

        assertFalse(result.contains("## 番外"));
    }

    @Test
    void exportMarkdown_projectNotFound_throwsException() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> exportService.exportMarkdown(99L));
    }

    // ==================== exportTxt ====================

    @Test
    void exportTxt_containsTitleSeparatorAndChapters() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));

        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(1);
        ch.setTitle("序章");
        ch.setContent("故事开始");
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of(ch));
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportTxt(1L);

        assertTrue(result.contains("测试小说"));
        assertTrue(result.contains("========================================"));
        assertTrue(result.contains("第1章 序章"));
        assertTrue(result.contains("故事开始"));
    }

    @Test
    void exportTxt_chapterWithoutTitleAndContent() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));

        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(3);
        ch.setTitle(null);
        ch.setContent(null);
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of(ch));
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());

        String result = exportService.exportTxt(1L);

        assertTrue(result.contains("第3章\n"));
    }

    @Test
    void exportTxt_includesSideStoriesAfterSeparator() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        SideStoryEntity ss = new SideStoryEntity();
        ss.setId(5L);
        ss.setTitle("外传");
        when(sideStoryRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of(ss));

        SideStoryChapterEntity ssCh = new SideStoryChapterEntity();
        ssCh.setChapterNumber(1);
        ssCh.setTitle(null);
        ssCh.setContent("外传正文");
        when(sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(5L)).thenReturn(List.of(ssCh));

        String result = exportService.exportTxt(1L);

        assertTrue(result.contains("番外"));
        assertTrue(result.contains("外传"));
        assertTrue(result.contains("外传正文"));
    }

    // ==================== exportJson ====================

    @Test
    void exportJson_returnsValidJsonWithVersion1() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(worldSettingFacetRepository.findByProjectId(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(1L)).thenReturn(List.of());
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(workflowStateRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepGuidanceRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepModelConfigRepository.findByProjectId(1L)).thenReturn(List.of());
        when(proofreadingReportRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        byte[] bytes = exportService.exportJson(1L);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        ObjectMapper mapper = new ObjectMapper();
        ProjectJsonDto dto = mapper.readValue(bytes, ProjectJsonDto.class);
        assertEquals(1, dto.version());
        assertEquals("测试小说", dto.project().title());
        assertEquals("QIHUAN", dto.project().genre());
    }

    @Test
    void exportJson_includesChapterData() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(worldSettingFacetRepository.findByProjectId(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(1L)).thenReturn(List.of());
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        ChapterEntity ch = new ChapterEntity();
        ch.setChapterNumber(1);
        ch.setTitle("开始");
        ch.setContent("内容");
        ch.setWordCount(2);
        ch.setStatus(StepStatus.GENERATED);
        ch.setPolishStatus(StepStatus.NOT_STARTED);
        ch.setProofreadStatus(StepStatus.NOT_STARTED);
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of(ch));

        when(workflowStateRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepGuidanceRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepModelConfigRepository.findByProjectId(1L)).thenReturn(List.of());
        when(proofreadingReportRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        byte[] bytes = exportService.exportJson(1L);
        ObjectMapper mapper = new ObjectMapper();
        ProjectJsonDto dto = mapper.readValue(bytes, ProjectJsonDto.class);

        assertEquals(1, dto.chapters().size());
        assertEquals(1, dto.chapters().get(0).chapterNumber());
        assertEquals("开始", dto.chapters().get(0).title());
    }

    @Test
    void exportJson_stepModelConfigsSkippedWhenConfigMissing() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(worldSettingFacetRepository.findByProjectId(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(1L)).thenReturn(List.of());
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(workflowStateRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepGuidanceRepository.findByProjectId(1L)).thenReturn(List.of());

        // StepModelConfig references configId=42, but config doesn't exist
        StepModelConfigEntity smc = new StepModelConfigEntity();
        smc.setStep(WorkflowStep.CHAPTER_WRITING);
        smc.setModelConfigId(42L);
        when(stepModelConfigRepository.findByProjectId(1L)).thenReturn(List.of(smc));
        when(aiModelConfigRepository.findById(42L)).thenReturn(Optional.empty());

        when(proofreadingReportRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        byte[] bytes = exportService.exportJson(1L);
        ObjectMapper mapper = new ObjectMapper();
        ProjectJsonDto dto = mapper.readValue(bytes, ProjectJsonDto.class);

        // Entry with missing config should be filtered out
        assertEquals(0, dto.stepModelConfigs().size());
    }

    @Test
    void exportJson_stepModelConfigsSkippedWhenConfigIdNull() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(makeProject()));
        when(worldSettingRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(worldSettingFacetRepository.findByProjectId(1L)).thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(1L)).thenReturn(List.of());
        when(storyOutlineRepository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(1L)).thenReturn(List.of());
        when(chapterOutlineRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(chapterRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());
        when(workflowStateRepository.findByProjectId(1L)).thenReturn(List.of());
        when(stepGuidanceRepository.findByProjectId(1L)).thenReturn(List.of());

        // StepModelConfig with null configId should be filtered
        StepModelConfigEntity smc = new StepModelConfigEntity();
        smc.setStep(WorkflowStep.CHAPTER_WRITING);
        smc.setModelConfigId(null);
        when(stepModelConfigRepository.findByProjectId(1L)).thenReturn(List.of(smc));

        when(proofreadingReportRepository.findByProjectIdOrderByChapterNumber(1L)).thenReturn(List.of());

        byte[] bytes = exportService.exportJson(1L);
        ObjectMapper mapper = new ObjectMapper();
        ProjectJsonDto dto = mapper.readValue(bytes, ProjectJsonDto.class);

        assertEquals(0, dto.stepModelConfigs().size());
    }
}
