package com.storycreator.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.entity.SideStoryChapterEntity;
import com.storycreator.persistence.repository.*;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExportService {

    private final ProjectRepository projectRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final StepModelConfigRepository stepModelConfigRepository;
    private final AiModelConfigRepository aiModelConfigRepository;
    private final ProofreadingReportRepository proofreadingReportRepository;
    private final SideStoryRepository sideStoryRepository;
    private final SideStoryChapterRepository sideStoryChapterRepository;
    private final WorldSettingFacetRepository worldSettingFacetRepository;
    private final ObjectMapper objectMapper;

    public ExportService(ProjectRepository projectRepository,
                        WorldSettingRepository worldSettingRepository,
                        CharacterRepository characterRepository,
                        StoryOutlineRepository storyOutlineRepository,
                        ChapterRepository chapterRepository,
                        VolumeOutlineRepository volumeOutlineRepository,
                        ChapterOutlineRepository chapterOutlineRepository,
                        WorkflowStateRepository workflowStateRepository,
                        StepGuidanceRepository stepGuidanceRepository,
                        StepModelConfigRepository stepModelConfigRepository,
                        AiModelConfigRepository aiModelConfigRepository,
                        ProofreadingReportRepository proofreadingReportRepository,
                        SideStoryRepository sideStoryRepository,
                        SideStoryChapterRepository sideStoryChapterRepository,
                        WorldSettingFacetRepository worldSettingFacetRepository,
                        ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.stepModelConfigRepository = stepModelConfigRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
        this.proofreadingReportRepository = proofreadingReportRepository;
        this.sideStoryRepository = sideStoryRepository;
        this.sideStoryChapterRepository = sideStoryChapterRepository;
        this.worldSettingFacetRepository = worldSettingFacetRepository;
        this.objectMapper = objectMapper;
    }

    public String exportMarkdown(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(project.getTitle()).append("\n\n");
        sb.append("> 题材：").append(project.getGenre().getDisplayName()).append("\n\n");

        if (project.getDescription() != null) {
            sb.append("## 简介\n\n").append(project.getDescription()).append("\n\n");
        }

        // World Setting
        worldSettingRepository.findByProjectId(projectId).ifPresent(ws -> {
            sb.append("## 世界观设定\n\n").append(ws.getContent()).append("\n\n");
        });

        // Characters
        List<CharacterEntity> chars = characterRepository.findByProjectIdOrderBySortOrder(projectId);
        if (!chars.isEmpty()) {
            sb.append("## 角色设计\n\n");
            for (CharacterEntity c : chars) {
                if (c.getContent() != null) {
                    sb.append(c.getContent()).append("\n\n");
                }
            }
        }

        // Outline
        storyOutlineRepository.findByProjectId(projectId).ifPresent(o -> {
            sb.append("## 故事大纲\n\n").append(o.getContent()).append("\n\n");
        });

        // Chapters
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        if (!chapters.isEmpty()) {
            sb.append("## 正文\n\n");
            for (ChapterEntity ch : chapters) {
                sb.append("### 第").append(ch.getChapterNumber()).append("章");
                if (ch.getTitle() != null) {
                    sb.append(" ").append(ch.getTitle());
                }
                sb.append("\n\n");
                if (ch.getContent() != null) {
                    sb.append(ch.getContent()).append("\n\n");
                }
            }
        }

        // Side Stories
        List<SideStoryEntity> sideStories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);
        if (!sideStories.isEmpty()) {
            sb.append("## 番外\n\n");
            for (SideStoryEntity ss : sideStories) {
                sb.append("### ").append(ss.getTitle()).append("\n\n");
                List<SideStoryChapterEntity> ssChapters = sideStoryChapterRepository
                        .findBySideStoryIdOrderByChapterNumber(ss.getId());
                for (SideStoryChapterEntity ssCh : ssChapters) {
                    sb.append("#### 第").append(ssCh.getChapterNumber()).append("章");
                    if (ssCh.getTitle() != null) {
                        sb.append(" ").append(ssCh.getTitle());
                    }
                    sb.append("\n\n");
                    if (ssCh.getContent() != null) {
                        sb.append(ssCh.getContent()).append("\n\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    public String exportTxt(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        StringBuilder sb = new StringBuilder();
        sb.append(project.getTitle()).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        for (ChapterEntity ch : chapters) {
            sb.append("第").append(ch.getChapterNumber()).append("章");
            if (ch.getTitle() != null) {
                sb.append(" ").append(ch.getTitle());
            }
            sb.append("\n");
            sb.append("-".repeat(30)).append("\n\n");
            if (ch.getContent() != null) {
                sb.append(ch.getContent()).append("\n\n\n");
            }
        }

        // Side Stories
        List<SideStoryEntity> sideStories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);
        if (!sideStories.isEmpty()) {
            sb.append("\n").append("=".repeat(40)).append("\n");
            sb.append("番外\n");
            sb.append("=".repeat(40)).append("\n\n");
            for (SideStoryEntity ss : sideStories) {
                sb.append(ss.getTitle()).append("\n");
                sb.append("-".repeat(30)).append("\n\n");
                List<SideStoryChapterEntity> ssChapters = sideStoryChapterRepository
                        .findBySideStoryIdOrderByChapterNumber(ss.getId());
                for (SideStoryChapterEntity ssCh : ssChapters) {
                    sb.append("第").append(ssCh.getChapterNumber()).append("章");
                    if (ssCh.getTitle() != null) sb.append(" ").append(ssCh.getTitle());
                    sb.append("\n\n");
                    if (ssCh.getContent() != null) sb.append(ssCh.getContent()).append("\n\n\n");
                }
            }
        }

        return sb.toString();
    }

    public byte[] exportPdf(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            com.lowagie.text.Document document = new com.lowagie.text.Document(
                    com.lowagie.text.PageSize.A4, 60, 60, 50, 50);
            com.lowagie.text.pdf.PdfWriter.getInstance(document, baos);
            document.open();

            // Use embedded CJK font for Chinese support
            com.lowagie.text.pdf.BaseFont bfChinese = com.lowagie.text.pdf.BaseFont.createFont(
                    "STSong-Light", "UniGB-UCS2-H", com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
            com.lowagie.text.Font titleFont = new com.lowagie.text.Font(bfChinese, 22, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font chapterFont = new com.lowagie.text.Font(bfChinese, 16, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font bodyFont = new com.lowagie.text.Font(bfChinese, 12, com.lowagie.text.Font.NORMAL);

            // Title page
            document.add(new com.lowagie.text.Paragraph("\n\n\n"));
            com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph(project.getTitle(), titleFont);
            title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            document.add(title);
            document.add(new com.lowagie.text.Paragraph("\n"));
            com.lowagie.text.Paragraph genre = new com.lowagie.text.Paragraph(
                    "题材：" + project.getGenre().getDisplayName(), bodyFont);
            genre.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            document.add(genre);
            document.newPage();

            // Chapters
            List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
            for (ChapterEntity ch : chapters) {
                String chTitle = "第" + ch.getChapterNumber() + "章";
                if (ch.getTitle() != null) chTitle += " " + ch.getTitle();

                com.lowagie.text.Paragraph chParagraph = new com.lowagie.text.Paragraph(chTitle, chapterFont);
                chParagraph.setSpacingBefore(10);
                chParagraph.setSpacingAfter(15);
                document.add(chParagraph);

                if (ch.getContent() != null) {
                    // Split by paragraphs
                    String[] paragraphs = ch.getContent().split("\n+");
                    for (String para : paragraphs) {
                        if (para.isBlank()) continue;
                        com.lowagie.text.Paragraph p = new com.lowagie.text.Paragraph(para, bodyFont);
                        p.setFirstLineIndent(24);
                        p.setSpacingAfter(6);
                        p.setLeading(20);
                        document.add(p);
                    }
                }
                document.newPage();
            }

            // Side Stories
            List<SideStoryEntity> sideStories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);
            if (!sideStories.isEmpty()) {
                com.lowagie.text.Paragraph sideTitle = new com.lowagie.text.Paragraph("番外", titleFont);
                sideTitle.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
                document.add(sideTitle);
                document.newPage();

                for (SideStoryEntity ss : sideStories) {
                    com.lowagie.text.Paragraph ssTitle = new com.lowagie.text.Paragraph(ss.getTitle(), chapterFont);
                    ssTitle.setSpacingBefore(10);
                    ssTitle.setSpacingAfter(15);
                    document.add(ssTitle);

                    List<SideStoryChapterEntity> ssChapters = sideStoryChapterRepository
                            .findBySideStoryIdOrderByChapterNumber(ss.getId());
                    for (SideStoryChapterEntity ssCh : ssChapters) {
                        String ssChTitle = "第" + ssCh.getChapterNumber() + "章";
                        if (ssCh.getTitle() != null) ssChTitle += " " + ssCh.getTitle();
                        com.lowagie.text.Paragraph chPara = new com.lowagie.text.Paragraph(ssChTitle, chapterFont);
                        chPara.setSpacingBefore(10);
                        chPara.setSpacingAfter(15);
                        document.add(chPara);

                        if (ssCh.getContent() != null) {
                            String[] paras = ssCh.getContent().split("\n+");
                            for (String para : paras) {
                                if (para.isBlank()) continue;
                                com.lowagie.text.Paragraph p = new com.lowagie.text.Paragraph(para, bodyFont);
                                p.setFirstLineIndent(24);
                                p.setSpacingAfter(6);
                                p.setLeading(20);
                                document.add(p);
                            }
                        }
                        document.newPage();
                    }
                }
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF", e);
        }
    }

    public byte[] exportEpub(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        try {
            io.documentnode.epub4j.domain.Book book = new io.documentnode.epub4j.domain.Book();
            var metadata = book.getMetadata();
            metadata.addTitle(project.getTitle());
            metadata.setLanguage("zh-CN");

            // Add chapters
            List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
            for (ChapterEntity ch : chapters) {
                String title = "第" + ch.getChapterNumber() + "章";
                if (ch.getTitle() != null) title += " " + ch.getTitle();

                String html = "<html><head><title>" + title + "</title></head><body>"
                        + "<h2>" + title + "</h2>"
                        + "<div>" + (ch.getContent() != null ? ch.getContent().replace("\n", "<br/>") : "") + "</div>"
                        + "</body></html>";

                book.addSection(title,
                        new io.documentnode.epub4j.domain.Resource(
                                html.getBytes("UTF-8"),
                                "chapter" + ch.getChapterNumber() + ".html"));
            }

            // Add side stories
            List<SideStoryEntity> sideStories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);
            for (SideStoryEntity ss : sideStories) {
                List<SideStoryChapterEntity> ssChapters = sideStoryChapterRepository
                        .findBySideStoryIdOrderByChapterNumber(ss.getId());
                for (SideStoryChapterEntity ssCh : ssChapters) {
                    String ssTitle = "番外·" + ss.getTitle() + " 第" + ssCh.getChapterNumber() + "章";
                    if (ssCh.getTitle() != null) ssTitle += " " + ssCh.getTitle();

                    String ssHtml = "<html><head><title>" + ssTitle + "</title></head><body>"
                            + "<h2>" + ssTitle + "</h2>"
                            + "<div>" + (ssCh.getContent() != null ? ssCh.getContent().replace("\n", "<br/>") : "") + "</div>"
                            + "</body></html>";

                    book.addSection(ssTitle,
                            new io.documentnode.epub4j.domain.Resource(
                                    ssHtml.getBytes("UTF-8"),
                                    "side_story_" + ss.getId() + "_ch" + ssCh.getChapterNumber() + ".html"));
                }
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            io.documentnode.epub4j.epub.EpubWriter writer = new io.documentnode.epub4j.epub.EpubWriter();
            writer.write(book, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EPUB", e);
        }
    }

    public byte[] exportJson(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        var projectData = new ProjectJsonDto.ProjectData(
                project.getTitle(),
                project.getGenre().name(),
                project.getDescription(),
                project.getCurrentStep().name(),
                project.getTotalChapters(),
                project.getChapterWordCount(),
                project.getChapterWordCountMin(),
                project.getChapterWordCountMax(),
                project.getCharacterCount(),
                project.getChaptersPerVolume(),
                project.isAutoMode()
        );

        Map<String, String> facetsMap = new LinkedHashMap<>();
        worldSettingFacetRepository.findByProjectId(projectId).forEach(f ->
                facetsMap.put(f.getFacetKey().name(), f.getContent()));
        var worldSetting = worldSettingRepository.findByProjectId(projectId)
                .map(ws -> new ProjectJsonDto.WorldSettingData(ws.getContent(), ws.getSummary(),
                        facetsMap.isEmpty() ? null : facetsMap))
                .orElse(null);

        var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .map(c -> new ProjectJsonDto.CharacterData(
                        c.getName(), c.getRole(), c.getGender(),
                        c.getPersonality(), c.getAppearance(), c.getBackground(),
                        c.getMotivation(), c.getRelationships(), c.getAbilities(),
                        c.getAge(), c.getDescription(), c.getContent(), c.getSummary(),
                        c.getSortOrder()))
                .toList();

        var storyOutline = storyOutlineRepository.findByProjectId(projectId)
                .map(o -> new ProjectJsonDto.StoryOutlineData(o.getContent(), o.getTotalChapters()))
                .orElse(null);

        var volumeOutlines = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId).stream()
                .map(v -> new ProjectJsonDto.VolumeOutlineData(
                        v.getVolumeNumber(), v.getTitle(), v.getArcSummary(),
                        v.getChapterStart(), v.getChapterEnd()))
                .toList();

        var chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .map(co -> new ProjectJsonDto.ChapterOutlineData(
                        co.getChapterNumber(), co.getTitle(), co.getSummary(),
                        co.getCharacterNames(), co.getVolumeNumber(), co.getStatus()))
                .toList();

        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .map(ch -> new ProjectJsonDto.ChapterData(
                        ch.getChapterNumber(), ch.getTitle(), ch.getContent(), ch.getContentDraft(),
                        ch.getWordCount(), ch.getStatus().name(),
                        ch.getPolishNote(), ch.getPolishStatus().name(),
                        ch.getProofreadStatus().name(), ch.getPlotSummary()))
                .toList();

        var workflowStates = workflowStateRepository.findByProjectId(projectId).stream()
                .map(ws -> new ProjectJsonDto.WorkflowStateData(
                        ws.getStep().name(), ws.getStatus().name(),
                        ws.getGeneratedContent(), ws.getUserEditedContent()))
                .toList();

        var stepGuidances = stepGuidanceRepository.findByProjectId(projectId).stream()
                .map(sg -> new ProjectJsonDto.StepGuidanceData(sg.getStep().name(), sg.getGuidance()))
                .toList();

        var stepModelConfigs = stepModelConfigRepository.findByProjectId(projectId).stream()
                .filter(smc -> smc.getModelConfigId() != null)
                .map(smc -> {
                    var config = aiModelConfigRepository.findById(smc.getModelConfigId()).orElse(null);
                    if (config == null) return null;
                    String modelCode = config.getProvider() + ":" + config.getModelId();
                    return new ProjectJsonDto.StepModelConfigData(smc.getStep().name(), modelCode);
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        var proofreadingReports = proofreadingReportRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .map(pr -> new ProjectJsonDto.ProofreadingReportData(
                        pr.getChapterNumber(), pr.getPlotSummary(),
                        pr.getCharacterIssues(), pr.getConsistencyIssues(),
                        pr.getContinuityIssues(), pr.getForeshadowing()))
                .toList();

        var dto = new ProjectJsonDto(1, projectData, worldSetting, characters, storyOutline,
                volumeOutlines, chapterOutlines, chapters, workflowStates, stepGuidances, stepModelConfigs, proofreadingReports);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON export", e);
        }
    }
}
