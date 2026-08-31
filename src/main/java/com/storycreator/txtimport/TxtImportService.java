package com.storycreator.txtimport;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.TxtImportChapterEntity;
import com.storycreator.persistence.entity.TxtImportJobEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.TxtImportChapterRepository;
import com.storycreator.persistence.repository.TxtImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TxtImportService {

    private static final Logger log = LoggerFactory.getLogger(TxtImportService.class);

    private final TxtImportJobRepository jobRepository;
    private final TxtImportChapterRepository importChapterRepository;
    private final ChapterSplitConfigRepository configRepository;
    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final TxtChapterSplitter splitter;

    public TxtImportService(TxtImportJobRepository jobRepository,
                            TxtImportChapterRepository importChapterRepository,
                            ChapterSplitConfigRepository configRepository,
                            ProjectRepository projectRepository,
                            ChapterRepository chapterRepository,
                            TxtChapterSplitter splitter) {
        this.jobRepository = jobRepository;
        this.importChapterRepository = importChapterRepository;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.splitter = splitter;
    }

    @Transactional
    public TxtImportJobEntity createJob(String title, String genre, String rawContent) {
        TxtImportJobEntity job = new TxtImportJobEntity();
        job.setTitle(title);
        job.setGenre(genre);
        job.setRawContent(rawContent);
        job.setStatus("PENDING");
        job.setTotalWordCount(rawContent.length());
        return jobRepository.save(job);
    }

    @Transactional
    public List<TxtImportChapterEntity> splitJob(Long jobId, List<Long> configIds) {
        TxtImportJobEntity job = getJob(jobId);
        job.setStatus("SPLITTING");
        jobRepository.save(job);

        // Delete existing chapters for re-split
        importChapterRepository.deleteByJobId(jobId);

        // Resolve configs
        List<ChapterSplitConfigEntity> configs;
        if (configIds != null && !configIds.isEmpty()) {
            configs = configIds.stream()
                    .map(id -> configRepository.findById(id).orElse(null))
                    .filter(c -> c != null)
                    .toList();
        } else {
            configs = configRepository.findByEnabledTrueOrderBySortOrder();
        }

        // Split
        List<TxtChapterSplitter.SplitChapter> splitResult = splitter.split(job.getRawContent(), configs);

        // Save chapters
        List<TxtImportChapterEntity> chapters = splitResult.stream().map(sc -> {
            TxtImportChapterEntity entity = new TxtImportChapterEntity();
            entity.setJobId(jobId);
            entity.setChapterNumber(sc.number());
            entity.setTitle(sc.title());
            entity.setContent(sc.content());
            entity.setWordCount(sc.wordCount());
            entity.setSortOrder(sc.number());
            return importChapterRepository.save(entity);
        }).toList();

        // Update job
        job.setStatus("SPLIT_DONE");
        job.setChapterCount(chapters.size());
        job.setTotalWordCount(chapters.stream().mapToInt(TxtImportChapterEntity::getWordCount).sum());
        jobRepository.save(job);

        return chapters;
    }

    @Transactional
    public void updateChapterTitle(Long jobId, int chapterNumber, String newTitle) {
        TxtImportChapterEntity chapter = importChapterRepository.findByJobIdAndChapterNumber(jobId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在"));
        chapter.setTitle(newTitle);
        importChapterRepository.save(chapter);
    }

    @Transactional
    public void mergeChapterWithNext(Long jobId, int chapterNumber) {
        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId);
        TxtImportChapterEntity current = null;
        TxtImportChapterEntity next = null;

        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getChapterNumber() == chapterNumber) {
                current = chapters.get(i);
                if (i + 1 < chapters.size()) {
                    next = chapters.get(i + 1);
                }
                break;
            }
        }

        if (current == null || next == null) {
            throw new IllegalArgumentException("无法合并：找不到相邻章节");
        }

        // Merge content
        String merged = current.getContent() + "\n\n" + next.getContent();
        current.setContent(merged);
        current.setWordCount(merged.length());
        importChapterRepository.save(current);

        // Remove next and renumber
        importChapterRepository.delete(next);
        renumberChapters(jobId);

        // Update job
        TxtImportJobEntity job = getJob(jobId);
        job.setChapterCount(importChapterRepository.countByJobId(jobId));
        jobRepository.save(job);
    }

    @Transactional
    public Long createProjectFromJob(Long jobId) {
        TxtImportJobEntity job = getJob(jobId);
        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId);

        // Create project
        ProjectEntity project = new ProjectEntity();
        project.setTitle(job.getTitle());
        if (job.getGenre() != null && !job.getGenre().isBlank()) {
            try {
                project.setGenre(Genre.valueOf(job.getGenre()));
            } catch (IllegalArgumentException e) {
                project.setGenre(Genre.OTHER);
            }
        } else {
            project.setGenre(Genre.OTHER);
        }
        project.setDescription("由TXT导入生成");
        project.setTotalChapters(chapters.size());
        project.setCurrentStep(WorkflowStep.WORLD_BUILDING);
        project = projectRepository.save(project);

        // Save chapters to the real chapters table
        Long projectId = project.getId();
        for (TxtImportChapterEntity importCh : chapters) {
            ChapterEntity chapterEntity = new ChapterEntity();
            chapterEntity.setProjectId(projectId);
            chapterEntity.setChapterNumber(importCh.getChapterNumber());
            chapterEntity.setTitle(importCh.getTitle());
            chapterEntity.setContent(importCh.getContent());
            chapterEntity.setWordCount(importCh.getWordCount());
            chapterRepository.save(chapterEntity);
        }

        // Update job with project reference
        job.setProjectId(projectId);
        jobRepository.save(job);

        log.info("Created project {} from TXT import job {}", projectId, jobId);
        return projectId;
    }

    public TxtImportJobEntity getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("导入任务不存在: " + jobId));
    }

    public List<TxtImportChapterEntity> getChapters(Long jobId) {
        return importChapterRepository.findByJobIdOrderByChapterNumber(jobId);
    }

    private void renumberChapters(Long jobId) {
        List<TxtImportChapterEntity> chapters = importChapterRepository.findByJobIdOrderByChapterNumber(jobId);
        int num = 1;
        for (TxtImportChapterEntity ch : chapters) {
            if (ch.getChapterNumber() != num) {
                ch.setChapterNumber(num);
                ch.setSortOrder(num);
                importChapterRepository.save(ch);
            }
            num++;
        }
    }
}
