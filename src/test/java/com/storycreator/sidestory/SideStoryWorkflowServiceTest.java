package com.storycreator.sidestory;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.Genre;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SideStoryWorkflowServiceTest {

    @Mock private SideStoryRepository sideStoryRepository;
    @Mock private SideStoryChapterRepository sideStoryChapterRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorldSettingRepository worldSettingRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private StoryOutlineRepository storyOutlineRepository;
    @Mock private VolumeOutlineRepository volumeOutlineRepository;
    @Mock private AiProviderRouter providerRouter;
    @Mock private PromptTemplateRegistry templateRegistry;
    @Mock private JdbcTemplate jdbcTemplate;

    private SideStoryWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new SideStoryWorkflowService(
                sideStoryRepository, sideStoryChapterRepository, projectRepository,
                worldSettingRepository, characterRepository, storyOutlineRepository,
                volumeOutlineRepository, providerRouter, templateRegistry, jdbcTemplate);
    }

    private ProjectEntity makeProject(Long id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setTitle("主线小说");
        p.setGenre(Genre.QIHUAN);
        p.setChapterWordCount(3000);
        return p;
    }

    private SideStoryEntity makeSideStory(Long id, Long projectId) {
        SideStoryEntity ss = new SideStoryEntity();
        ss.setId(id);
        ss.setProjectId(projectId);
        ss.setTitle("外传标题");
        ss.setDescription("外传描述");
        ss.setCreativeGuidance("创意引导");
        ss.setAttachedVolume(null);
        return ss;
    }

    // ==================== extractArcName (via saveOutline) ====================

    @Test
    void saveOutline_extractsArcNameFromChineseColon() {
        SideStoryEntity ss = makeSideStory(1L, 10L);
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveOutline(1L, "故事弧线：英雄成长\n正文内容");

        verify(sideStoryRepository).save(argThat(s ->
                "英雄成长".equals(s.getArcName()) && "OUTLINE_READY".equals(s.getStatus())));
    }

    @Test
    void saveOutline_extractsArcNameFromAsciiColon() {
        SideStoryEntity ss = makeSideStory(1L, 10L);
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveOutline(1L, "故事弧线:命运转折\n内容");

        verify(sideStoryRepository).save(argThat(s -> "命运转折".equals(s.getArcName())));
    }

    @Test
    void saveOutline_noArcNamePatternDoesNotSetArcName() {
        SideStoryEntity ss = makeSideStory(1L, 10L);
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveOutline(1L, "第一章：故事开始\n没有弧线信息");

        verify(sideStoryRepository).save(argThat(s -> s.getArcName() == null));
    }

    @Test
    void saveOutline_emptyContentDoesNotSetArcName() {
        SideStoryEntity ss = makeSideStory(1L, 10L);
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveOutline(1L, "");

        verify(sideStoryRepository).save(argThat(s -> s.getArcName() == null));
    }

    // ==================== extractTitleFromOutline (via saveChapterOutline) ====================

    @Test
    void saveChapterOutline_extractsTitleWithChinesePrefix() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(1);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        service.saveChapterOutline(1L, 1, "章节标题：命运的抉择\n正文内容");

        verify(sideStoryChapterRepository).save(argThat(c -> "命运的抉择".equals(c.getTitle())));
    }

    @Test
    void saveChapterOutline_extractsTitleWithMarkdownHeader() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(2);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 2))
                .thenReturn(Optional.of(ch));

        service.saveChapterOutline(1L, 2, "## 命运之门\n内容");

        verify(sideStoryChapterRepository).save(argThat(c -> "命运之门".equals(c.getTitle())));
    }

    @Test
    void saveChapterOutline_shortFirstLineUsedAsTitle() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(3);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 3))
                .thenReturn(Optional.of(ch));

        service.saveChapterOutline(1L, 3, "归途\n很长的正文内容描述");

        verify(sideStoryChapterRepository).save(argThat(c -> "归途".equals(c.getTitle())));
    }

    @Test
    void saveChapterOutline_longFirstLineWithPeriodNotUsedAsTitle() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(4);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 4))
                .thenReturn(Optional.of(ch));

        // More than 30 chars or contains "。" -> not a title
        service.saveChapterOutline(1L, 4, "这是一段很长的正文内容，包含句号。第一章开始了。");

        verify(sideStoryChapterRepository).save(argThat(c -> c.getTitle() == null));
    }

    @Test
    void saveChapterOutline_setsOutlineSummaryAndStatus() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(1);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        service.saveChapterOutline(1L, 1, "内容");

        verify(sideStoryChapterRepository).save(argThat(c ->
                "内容".equals(c.getOutlineSummary()) && "NOT_STARTED".equals(c.getStatus())));
    }

    // ==================== saveChapterContent ====================

    @Test
    void saveChapterContent_setsContentAndStatusCompleted() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(1);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setStatus("OUTLINE_READY");
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveChapterContent(1L, 1, "章节正文内容");

        verify(sideStoryChapterRepository).save(argThat(c ->
                "章节正文内容".equals(c.getContent())
                && c.getWordCount() == "章节正文内容".length()
                && "COMPLETED".equals(c.getStatus())));
    }

    @Test
    void saveChapterContent_updatesSideStoryStatusToInProgress() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(1);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setStatus("OUTLINE_READY");
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveChapterContent(1L, 1, "内容");

        verify(sideStoryRepository).save(argThat(s -> "IN_PROGRESS".equals(s.getStatus())));
    }

    @Test
    void saveChapterContent_doesNotUpdateStatusIfAlreadyInProgress() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(2);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 2))
                .thenReturn(Optional.of(ch));

        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setStatus("IN_PROGRESS");  // already IN_PROGRESS
        when(sideStoryRepository.findById(1L)).thenReturn(Optional.of(ss));

        service.saveChapterContent(1L, 2, "内容");

        // sideStoryRepository.save should not be called for status update
        verify(sideStoryRepository, never()).save(any());
    }

    // ==================== savePolishedContent ====================

    @Test
    void savePolishedContent_setsStatusPolished() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setSideStoryId(1L);
        ch.setChapterNumber(1);
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        service.savePolishedContent(1L, 1, "润色后内容");

        verify(sideStoryChapterRepository).save(argThat(c ->
                "润色后内容".equals(c.getContent())
                && c.getWordCount() == "润色后内容".length()
                && "POLISHED".equals(c.getStatus())));
    }

    // ==================== buildOutlineVariables ====================

    @Test
    void buildOutlineVariables_noAttachedVolumeReturnsDefaultText() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setAttachedVolume(null);

        when(worldSettingRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(storyOutlineRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        // No characters selected
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        Map<String, String> vars = service.buildOutlineVariables(project, ss);

        assertEquals("外传标题", vars.get("sideStoryTitle"));
        assertEquals("外传描述", vars.get("sideStoryDescription"));
        assertEquals("创意引导", vars.get("creativeGuidance"));
        assertEquals("无特定关联卷", vars.get("attachedVolumeContext"));
        assertEquals("", vars.get("worldSetting"));
        assertEquals("", vars.get("storySummary"));
    }

    @Test
    void buildOutlineVariables_withAttachedVolumeAndNoVolumeData() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setAttachedVolume(2);

        when(worldSettingRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(storyOutlineRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());
        when(volumeOutlineRepository.findByProjectIdAndVolumeNumber(10L, 2))
                .thenReturn(Optional.empty());

        Map<String, String> vars = service.buildOutlineVariables(project, ss);

        assertEquals("关联卷 2（无详细信息）", vars.get("attachedVolumeContext"));
    }

    @Test
    void buildOutlineVariables_withAttachedVolumeData() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setAttachedVolume(1);

        when(worldSettingRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(storyOutlineRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        VolumeOutlineEntity vol = new VolumeOutlineEntity();
        vol.setVolumeNumber(1);
        vol.setTitle("第一卷标题");
        vol.setArcSummary("第一卷弧线");
        when(volumeOutlineRepository.findByProjectIdAndVolumeNumber(10L, 1))
                .thenReturn(Optional.of(vol));

        Map<String, String> vars = service.buildOutlineVariables(project, ss);

        String ctx = vars.get("attachedVolumeContext");
        assertTrue(ctx.contains("第1卷"));
        assertTrue(ctx.contains("第一卷标题"));
        assertTrue(ctx.contains("第一卷弧线"));
    }

    @Test
    void buildOutlineVariables_charactersBySummaryWhenAvailable() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setAttachedVolume(null);

        when(worldSettingRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(storyOutlineRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());

        CharacterEntity c = new CharacterEntity();
        c.setName("主角");
        c.setSummary("主角摘要");
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of(c));

        Map<String, String> vars = service.buildOutlineVariables(project, ss);

        assertTrue(vars.get("allCharacters").contains("【主角】"));
        assertTrue(vars.get("allCharacters").contains("主角摘要"));
    }

    @Test
    void buildOutlineVariables_charactersByContentWhenNoSummary() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setAttachedVolume(null);

        when(worldSettingRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(storyOutlineRepository.findByProjectId(10L)).thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());

        CharacterEntity c = new CharacterEntity();
        c.setName("主角");
        c.setSummary(null);
        c.setContent("详细内容");
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of(c));

        Map<String, String> vars = service.buildOutlineVariables(project, ss);

        assertTrue(vars.get("allCharacters").contains("详细内容"));
    }

    // ==================== buildChapterOutlineVariables ====================

    @Test
    void buildChapterOutlineVariables_noPreviousChapters() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setOutline("整体大纲");

        when(sideStoryChapterRepository.countBySideStoryId(1L)).thenReturn(3);
        when(sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(1L))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        Map<String, String> vars = service.buildChapterOutlineVariables(project, ss, 1);

        assertEquals("1", vars.get("chapterNumber"));
        assertEquals("3", vars.get("totalChapters"));
        assertEquals("", vars.get("previousOutlines"));
    }

    @Test
    void buildChapterOutlineVariables_includesPreviousChapterOutlines() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setOutline("整体大纲");

        when(sideStoryChapterRepository.countBySideStoryId(1L)).thenReturn(3);

        SideStoryChapterEntity prev = new SideStoryChapterEntity();
        prev.setChapterNumber(1);
        prev.setTitle("第一章");
        prev.setOutlineSummary("第一章摘要");
        when(sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(1L))
                .thenReturn(List.of(prev));
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        Map<String, String> vars = service.buildChapterOutlineVariables(project, ss, 2);

        assertTrue(vars.get("previousOutlines").contains("第1章"));
        assertTrue(vars.get("previousOutlines").contains("第一章摘要"));
    }

    // ==================== buildWritingVariables ====================

    @Test
    void buildWritingVariables_firstChapterHasNoFrontContext() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setOutline("大纲");

        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setChapterNumber(1);
        ch.setTitle("第一章");
        ch.setOutlineSummary("本章摘要");
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 0))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        Map<String, String> vars = service.buildWritingVariables(project, ss, 1);

        assertEquals("无前文（这是第一章）", vars.get("previousContext"));
        assertEquals("1", vars.get("chapterNumber"));
        assertEquals("3000", vars.get("chapterWordCount"));
    }

    @Test
    void buildWritingVariables_laterChapterUsesPreviousOutlineSummary() {
        ProjectEntity project = makeProject(10L);
        SideStoryEntity ss = makeSideStory(1L, 10L);
        ss.setOutline("大纲");

        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setChapterNumber(2);
        ch.setTitle("第二章");
        ch.setOutlineSummary("第二章摘要");
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 2))
                .thenReturn(Optional.of(ch));

        SideStoryChapterEntity prev = new SideStoryChapterEntity();
        prev.setChapterNumber(1);
        prev.setOutlineSummary("第一章摘要作为上下文");
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(prev));

        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of());
        when(characterRepository.findByProjectIdOrderBySortOrder(10L)).thenReturn(List.of());

        Map<String, String> vars = service.buildWritingVariables(project, ss, 2);

        assertEquals("第一章摘要作为上下文", vars.get("previousContext"));
    }

    // ==================== resetChapterStatus ====================

    @Test
    void resetChapterStatus_setsProvidedStatus() {
        SideStoryChapterEntity ch = new SideStoryChapterEntity();
        ch.setStatus("GENERATING");
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 1))
                .thenReturn(Optional.of(ch));

        service.resetChapterStatus(1L, 1, "NOT_STARTED");

        verify(sideStoryChapterRepository).save(argThat(c -> "NOT_STARTED".equals(c.getStatus())));
    }

    @Test
    void resetChapterStatus_noOpWhenChapterNotFound() {
        when(sideStoryChapterRepository.findBySideStoryIdAndChapterNumber(1L, 99))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.resetChapterStatus(1L, 99, "NOT_STARTED"));
        verify(sideStoryChapterRepository, never()).save(any());
    }
}
