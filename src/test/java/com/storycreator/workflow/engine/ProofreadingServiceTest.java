package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.StepStatus;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProofreadingReportEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProofreadingReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProofreadingServiceTest {

    private ProofreadingService service;

    @Mock private ChapterRepository chapterRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private ProofreadingReportRepository proofreadingReportRepository;
    @Mock private AiProviderRouter providerRouter;
    @Mock private PromptTemplateRegistry promptRegistry;
    @Mock private AiUsageTracker aiUsageTracker;
    @Mock private GlobalSettingService globalSettingService;

    @BeforeEach
    void setUp() {
        service = new ProofreadingService(chapterRepository, characterRepository,
                proofreadingReportRepository, providerRouter, promptRegistry,
                aiUsageTracker, globalSettingService);
    }

    @Test
    void constructor_doesNotRequireChapterOutlineRepository() {
        // Verify that ProofreadingService has exactly 7 constructor parameters
        // (no ChapterOutlineRepository)
        Constructor<?>[] constructors = ProofreadingService.class.getConstructors();
        assertEquals(1, constructors.length);
        assertEquals(7, constructors[0].getParameterCount(),
                "ProofreadingService should have 7 constructor parameters (no ChapterOutlineRepository)");
    }

    @Test
    void saveProofreadingResults_savesToReportAndChapter_notOutline() throws Exception {
        // Use reflection to call the private saveProofreadingResults method
        var method = ProofreadingService.class.getDeclaredMethod("saveProofreadingResults",
                ChapterEntity.class, String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);

        ChapterEntity chapter = new ChapterEntity();
        chapter.setProjectId(1L);
        chapter.setChapterNumber(3);
        chapter.setContent("章节内容");

        when(proofreadingReportRepository.findByProjectIdAndChapterNumber(1L, 3))
                .thenReturn(Optional.empty());

        method.invoke(service, chapter, "剧情摘要", "角色问题", "一致性问题", "衔接问题", "伏笔");

        // Verify report was saved
        verify(proofreadingReportRepository).save(argThat(report ->
                "剧情摘要".equals(report.getPlotSummary()) &&
                "角色问题".equals(report.getCharacterIssues())));

        // Verify chapter was saved with plot summary and proofread status
        assertEquals("剧情摘要", chapter.getPlotSummary());
        assertEquals(StepStatus.GENERATED, chapter.getProofreadStatus());
        verify(chapterRepository).save(chapter);
    }
}
