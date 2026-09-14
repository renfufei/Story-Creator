package com.storycreator.web;

import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.sidestory.SideStoryWorkflowService;
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
 * 番外篇（side-stories）静态页引导数据。
 *
 * <p>前端已从 Thymeleaf 迁移为「静态 HTML + JS + Ajax」：{@code static/pages/side-story-list.html}
 * 与 {@code side-story.html} 通过同步 XHR 拉取本控制器提供的 JSON 做引导渲染，页面本身不再由模板引擎渲染。
 *
 * <p>URL 转发（{@code /projects/{projectId}/side-stories} 与 {@code /{id}}）由 {@link StaticPageController} 负责，
 * 这里只提供数据；项目 / 番外不存在时返回 404。
 */
@RestController
@RequestMapping("/projects/{projectId}/side-stories")
public class SideStoryController {

    private final ProjectRepository projectRepository;
    private final SideStoryRepository sideStoryRepository;
    private final SideStoryChapterRepository sideStoryChapterRepository;
    private final CharacterRepository characterRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final SideStoryWorkflowService workflowService;

    public SideStoryController(ProjectRepository projectRepository,
                                SideStoryRepository sideStoryRepository,
                                SideStoryChapterRepository sideStoryChapterRepository,
                                CharacterRepository characterRepository,
                                VolumeOutlineRepository volumeOutlineRepository,
                                SideStoryWorkflowService workflowService) {
        this.projectRepository = projectRepository;
        this.sideStoryRepository = sideStoryRepository;
        this.sideStoryChapterRepository = sideStoryChapterRepository;
        this.characterRepository = characterRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.workflowService = workflowService;
    }

    /** 番外列表页引导数据（供 {@code side-story-list.html} 同步 XHR 拉取）。 */
    @GetMapping("/list-data")
    public ResponseEntity<Map<String, Object>> listData(@PathVariable Long projectId) {
        return projectRepository.findById(projectId)
                .map(project -> ResponseEntity.ok(buildListData(projectId, project.getTitle())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 番外详情页引导数据（供 {@code side-story.html} 同步 XHR 拉取）。 */
    @GetMapping("/{id}/data")
    public ResponseEntity<Map<String, Object>> detailData(@PathVariable Long projectId, @PathVariable Long id) {
        var projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var sideStoryOpt = sideStoryRepository.findById(id);
        if (sideStoryOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("projectTitle", projectOpt.get().getTitle() != null ? projectOpt.get().getTitle() : "");
        data.put("sideStory", sideStoryOpt.get());
        data.put("chapters", sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(id));
        data.put("characterIds", workflowService.getCharacterIds(id));
        data.put("characters", toCharacterMaps(projectId));
        data.put("volumes", volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId));
        return ResponseEntity.ok(data);
    }

    private Map<String, Object> buildListData(Long projectId, String projectTitle) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("projectTitle", projectTitle != null ? projectTitle : "");

        List<Map<String, Object>> sideStories = new ArrayList<>();
        for (var ss : sideStoryRepository.findByProjectIdOrderBySortOrder(projectId)) {
            int count = sideStoryChapterRepository.countBySideStoryId(ss.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("story", ss);
            item.put("chapterCount", count);
            sideStories.add(item);
        }
        data.put("sideStories", sideStories);
        data.put("characters", toCharacterMaps(projectId));
        data.put("volumes", volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId));
        return data;
    }

    /** 角色只暴露静态页需要的 id / name / role，避免直接序列化实体可能带来的关联递归。 */
    private List<Map<String, Object>> toCharacterMaps(Long projectId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CharacterEntity c : characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("role", c.getRole());
            result.add(m);
        }
        return result;
    }
}
