package com.storycreator.txtimport;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.TxtImportChapterEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.TxtImportChapterRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TXT 导入任务服务：项目复用（断点续跑前提）、重新分割的幂等、作者回退。
 *
 * <p>之前这些行为是手工 {@code curl} 验证的（日志里能看到 {@code Reuse project 22 for TXT import job 12}），
 * 此处固化成自动化用例，防止再次出现「每次续跑都重建项目导致进度归零」这类回归。
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:txt_import_service_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class TxtImportServiceTest {

    @Autowired private TxtImportJobRepository jobRepository;
    @Autowired private TxtImportChapterRepository importChapterRepository;
    @Autowired private ChapterSplitConfigRepository configRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ChapterRepository chapterRepository;

    private TxtChapterSplitter splitter;
    private GlobalSettingService globalSettingService;
    private TxtImportService service;

    @BeforeEach
    void setUp() {
        splitter = mock(TxtChapterSplitter.class);
        globalSettingService = mock(GlobalSettingService.class);
        
        service = new TxtImportService();
        service.setJobRepository(jobRepository);
        service.setImportChapterRepository(importChapterRepository);
        service.setConfigRepository(configRepository);
        service.setProjectRepository(projectRepository);
        service.setChapterRepository(chapterRepository);
        service.setSplitter(splitter);
        service.setGlobalSettingService(globalSettingService);
    }

    @Test
    void createJob_setsFieldsAndPendingStatus() {
        TxtImportJobEntity job = service.createJob("书名", "FANTASY", "张三", "正文内容");

        assertThat(job.getId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getAuthor()).isEqualTo("张三");
        assertThat(job.getTotalWordCount()).isEqualTo("正文内容".length());
    }

    @Test
    void ensureProjectFromJob_createsOnceThenReusesSameProjectOnResume() {
        TxtImportJobEntity job = newJob("续跑测试", 3);
        addChapter(job.getId(), 1, "第1章", "内容一");
        addChapter(job.getId(), 2, "第2章", "内容二");

        Long first = service.ensureProjectFromJob(job.getId());
        assertThat(first).isNotNull();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getProjectId()).isEqualTo(first);
        assertThat(projectRepository.count()).isEqualTo(1);

        // 续跑场景：改了每卷章节数、又多导入一章 → 再次调用必须复用同一项目并刷新元数据
        TxtImportJobEntity reloaded = jobRepository.findById(job.getId()).orElseThrow();
        reloaded.setChaptersPerVolume(4);
        jobRepository.save(reloaded);
        addChapter(job.getId(), 3, "第3章", "内容三");

        Long second = service.ensureProjectFromJob(job.getId());

        assertThat(second).isEqualTo(first);                 // 关键：不新建项目
        assertThat(projectRepository.count()).isEqualTo(1);  // 仍然只有一个项目
        ProjectEntity project = projectRepository.findById(first).orElseThrow();
        assertThat(project.getChaptersPerVolume()).isEqualTo(4);
        assertThat(project.getTotalChapters()).isEqualTo(3);
    }

    @Test
    void splitJob_isIdempotentOnReSplit() {
        TxtImportJobEntity job = newJob("重分割测试", 3);
        job.setRawContent("第1章 内容\n第2章 内容");
        jobRepository.save(job);

        when(splitter.split(any(), any())).thenReturn(List.of(
                new SplitChapter(1, "第1章", "内容一", 3),
                new SplitChapter(2, "第2章", "内容二", 3)));

        // 第一次分割
        service.splitJob(job.getId(), null);
        // 第二次分割：旧章节必须先被删除，否则会撞 (job_id, chapter_number) 唯一键
        service.splitJob(job.getId(), null);

        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(job.getId());
        assertThat(chapters).hasSize(2);
        assertThat(chapters).extracting(TxtImportChapterEntity::getChapterNumber).containsExactly(1, 2);
        TxtImportJobEntity after = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SPLIT_DONE");
        assertThat(after.getChapterCount()).isEqualTo(2);
    }

    @Test
    void createProjectFromJob_usesExplicitAuthor() {
        TxtImportJobEntity job = newJob("作者测试", 3);
        job.setAuthor("李四");
        jobRepository.save(job);
        addChapter(job.getId(), 1, "第1章", "内容一");

        Long projectId = service.createProjectFromJob(job.getId());

        assertThat(projectRepository.findById(projectId).orElseThrow().getAuthor()).isEqualTo("李四");
    }

    @Test
    void createProjectFromJob_fallsBackToGlobalDefaultAuthor() {
        TxtImportJobEntity job = newJob("作者回退测试", 3);
        job.setAuthor(null);
        jobRepository.save(job);
        addChapter(job.getId(), 1, "第1章", "内容一");
        when(globalSettingService.getDefaultAuthor()).thenReturn("全局默认作者");

        Long projectId = service.createProjectFromJob(job.getId());

        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getAuthor()).isEqualTo("全局默认作者");
        assertThat(project.getGenre()).isEqualTo(Genre.XUANHUAN);
        assertThat(project.getTotalChapters()).isEqualTo(1);
        // 导入的章节应同步落到正式章节表
        assertThat(chapterRepository.findByProjectIdOrderByChapterNumber(projectId)).hasSize(1);
    }

    // ==================================================================
    // 第 2 步「保存并开启下一步」：保存基础项目信息
    // ==================================================================

    @Test
    void saveBasicInfo_createsProjectThenSyncsBasicsWithoutCreatingAnother() {
        TxtImportJobEntity job = newJob("原始标题", 3);
        addChapter(job.getId(), 1, "第1章", "内容一");

        // 首次点击：立即建项目并写入基础信息（不再等到开始逆向工程）
        Long projectId = service.saveBasicInfo(job.getId(), "和青梅做了十年朋友后", "YANQING", "晨曦之主");

        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getTitle()).isEqualTo("和青梅做了十年朋友后");
        assertThat(project.getGenre()).isEqualTo(Genre.YANQING);
        assertThat(project.getAuthor()).isEqualTo("晨曦之主");
        assertThat(project.getTotalChapters()).isEqualTo(1);
        assertThat(projectRepository.count()).isEqualTo(1);

        // 回第 1 步改标题后重存：必须复用同一项目并同步基础信息，不得新建
        Long again = service.saveBasicInfo(job.getId(), "改过的标题", "YANQING", "晨曦之主");

        assertThat(again).isEqualTo(projectId);
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(projectRepository.findById(projectId).orElseThrow().getTitle()).isEqualTo("改过的标题");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getTitle()).isEqualTo("改过的标题");
    }

    @Test
    void saveBasicInfo_blankGenreFallsBackToOtherAndBlankAuthorToGlobalDefault() {
        TxtImportJobEntity job = newJob("留空测试", 3);
        job.setAuthor("原作者");
        jobRepository.save(job);
        addChapter(job.getId(), 1, "第1章", "内容一");
        when(globalSettingService.getDefaultAuthor()).thenReturn("全局默认作者");

        Long projectId = service.saveBasicInfo(job.getId(), "留空测试", "  ", "  ");

        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getGenre()).isEqualTo(Genre.OTHER);
        assertThat(project.getAuthor()).isEqualTo("全局默认作者");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getGenre()).isNull();
    }

    // ==================================================================
    // 测试数据
    // ==================================================================

    private TxtImportJobEntity newJob(String title, int chaptersPerVolume) {
        TxtImportJobEntity job = new TxtImportJobEntity();
        job.setTitle(title);
        job.setGenre("XUANHUAN");
        job.setChaptersPerVolume(chaptersPerVolume);
        job.setStatus("SPLIT_DONE");
        return jobRepository.save(job);
    }

    private void addChapter(Long jobId, int number, String title, String content) {
        TxtImportChapterEntity chapter = new TxtImportChapterEntity();
        chapter.setJobId(jobId);
        chapter.setChapterNumber(number);
        chapter.setTitle(title);
        chapter.setContent(content);
        chapter.setWordCount(content.length());
        chapter.setSortOrder(number);
        importChapterRepository.save(chapter);
    }
}
