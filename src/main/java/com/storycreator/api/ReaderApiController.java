package com.storycreator.api;

import org.springframework.beans.factory.annotation.Autowired;

import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.SideStoryChapterEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全文阅读页（{@code static/pages/reader.html}）的引导数据。
 *
 * <p>原来由 {@code ProjectController#readProject} 用 Thymeleaf 服务端渲染，静态化后改为
 * 一次性返回阅读页所需的全部数据（含章节正文），由前端 Alpine 渲染。
 *
 * <p>仅返回数据，不做跳转；项目不存在返回 404。
 */
@RestController
@RequestMapping("/api/projects")
public class ReaderApiController {

    private ProjectRepository projectRepository;
    private ChapterRepository chapterRepository;
    private VolumeOutlineRepository volumeOutlineRepository;
    private SideStoryRepository sideStoryRepository;
    private SideStoryChapterRepository sideStoryChapterRepository;

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setVolumeOutlineRepository(VolumeOutlineRepository volumeOutlineRepository) {
        this.volumeOutlineRepository = volumeOutlineRepository;
    }

    @Autowired
    public void setSideStoryRepository(SideStoryRepository sideStoryRepository) {
        this.sideStoryRepository = sideStoryRepository;
    }

    @Autowired
    public void setSideStoryChapterRepository(SideStoryChapterRepository sideStoryChapterRepository) {
        this.sideStoryChapterRepository = sideStoryChapterRepository;
    }


    /** 阅读页引导数据：项目信息 + 分卷 + 章节（含正文）+ 番外（含正文）。 */
    @GetMapping("/{id}/read-data")
    public ResponseEntity<Map<String, Object>> readData(@PathVariable Long id) {
        return projectRepository.findById(id)
                .map(project -> ResponseEntity.ok(buildReadData(project)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> buildReadData(ProjectEntity project) {
        Long projectId = project.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("projectTitle", project.getTitle() != null ? project.getTitle() : "");
        data.put("author", project.getAuthor() != null ? project.getAuthor() : "");

        // 分卷（目录用）
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (var v : volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("volumeNumber", v.getVolumeNumber());
            vm.put("title", v.getTitle() != null ? v.getTitle() : "");
            vm.put("chapterStart", v.getChapterStart());
            vm.put("chapterEnd", v.getChapterEnd());
            volumes.add(vm);
        }
        data.put("volumes", volumes);

        // 章节（目录 + 正文共用）
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (ChapterEntity c : chapterRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("number", c.getChapterNumber());
            cm.put("title", c.getTitle() != null ? c.getTitle() : "");
            cm.put("content", c.getContent() != null ? c.getContent() : "");
            cm.put("expansionStatus", c.getExpansionStatus());
            chapters.add(cm);
        }
        data.put("chapters", chapters);

        // 番外（目录 + 正文共用）；id 用字符串，与前端 openSideStories（String）比较保持一致
        List<Map<String, Object>> sideStories = new ArrayList<>();
        for (var ss : sideStoryRepository.findByProjectIdOrderBySortOrder(projectId)) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", String.valueOf(ss.getId()));
            sm.put("title", ss.getTitle() != null ? ss.getTitle() : "");
            sm.put("attachedVolume", ss.getAttachedVolume());

            List<Map<String, Object>> ssChapters = new ArrayList<>();
            for (SideStoryChapterEntity ch : sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(ss.getId())) {
                Map<String, Object> chm = new LinkedHashMap<>();
                chm.put("id", ch.getId());
                chm.put("number", ch.getChapterNumber());
                chm.put("title", ch.getTitle() != null ? ch.getTitle() : "");
                chm.put("content", ch.getContent() != null ? ch.getContent() : "");
                ssChapters.add(chm);
            }
            sm.put("chapters", ssChapters);
            sideStories.add(sm);
        }
        data.put("sideStories", sideStories);

        return data;
    }
}
