package com.storycreator.api;

import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.VolumeOutlineEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.volume.VolumeChapterGroup;
import com.storycreator.volume.VolumeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分卷管理 API：章节 ↔ 分卷 的显式绑定读写。
 *
 * <p>提供 {@code /projects/{projectId}/volumes/manage} 页面所需的数据与操作，
 * 「重建 / 移动章节 / 新增 / 改名 / 删除」全部走这里；业务规则集中在 {@link VolumeService}。</p>
 *
 * <p>设计前提：<b>兼容存量数据</b>。项目未开启绑定开关（{@code volume_binding_enabled=FALSE}，
 * 所有老项目的默认值）时，卷归属仍按 {@code chaptersPerVolume} 整除推算；只有用户在本页面
 * 做过一次调整后才切换到显式绑定。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/volumes")
public class VolumeApiController {

    private VolumeService volumeService;
    private VolumeOutlineRepository volumeOutlineRepository;
    private ChapterRepository chapterRepository;
    private ProjectRepository projectRepository;

    @Autowired
    public void setVolumeService(VolumeService volumeService) {
        this.volumeService = volumeService;
    }

    @Autowired
    public void setVolumeOutlineRepository(VolumeOutlineRepository volumeOutlineRepository) {
        this.volumeOutlineRepository = volumeOutlineRepository;
    }

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public record RebuildRequest(Integer chaptersPerVolume) {
    }

    public record AssignRequest(List<Integer> chapterNumbers) {
    }

    public record CreateVolumeRequest(String title) {
    }

    public record RenameVolumeRequest(String title) {
    }

    /** 页面引导数据：项目 + 全部分卷（含实际章节号）+ 全部章节。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("projectTitle", project.getTitle());
        data.put("chaptersPerVolume", volumeService.effectivePerVolume(project));
        data.put("bindingEnabled", project.isVolumeBindingEnabled());

        Map<Integer, String> arcSummaryByNumber = new LinkedHashMap<>();
        Map<Long, Integer> numberByVolumeId = new LinkedHashMap<>();
        for (VolumeOutlineEntity v : volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId)) {
            arcSummaryByNumber.put(v.getVolumeNumber(), v.getArcSummary());
            numberByVolumeId.put(v.getId(), v.getVolumeNumber());
        }

        List<VolumeChapterGroup> groups = volumeService.resolveGroups(projectId);
        List<Map<String, Object>> volumes = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        for (VolumeChapterGroup g : groups) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("id", g.volumeId());
            vm.put("volumeNumber", g.volumeNumber());
            vm.put("title", g.title() != null ? g.title() : "");
            vm.put("arcName", g.arcName() != null ? g.arcName() : "");
            vm.put("arcSummary", arcSummaryByNumber.getOrDefault(g.volumeNumber(), ""));
            List<Integer> numbers = g.chapterNumbers();
            vm.put("chapterNumbers", numbers);
            vm.put("chapterCount", numbers.size());
            vm.put("chapterStart", numbers.isEmpty() ? 0 : numbers.get(0));
            vm.put("chapterEnd", numbers.isEmpty() ? 0 : numbers.get(numbers.size() - 1));
            volumes.add(vm);
            used.addAll(numbers);
        }
        data.put("volumes", volumes);

        List<Map<String, Object>> chapters = new ArrayList<>();
        List<Integer> unbound = new ArrayList<>();
        for (ChapterEntity c : chapterRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("number", c.getChapterNumber());
            cm.put("title", c.getTitle() != null ? c.getTitle() : "");
            cm.put("wordCount", c.getWordCount());
            Long vid = c.getVolumeId();
            cm.put("volumeId", vid);
            cm.put("volumeNumber", vid == null ? null : numberByVolumeId.get(vid));
            if (vid == null || !numberByVolumeId.containsKey(vid)) {
                unbound.add(c.getChapterNumber());
            }
            chapters.add(cm);
        }
        data.put("chapters", chapters);
        data.put("unboundChapterNumbers", unbound);
        return ResponseEntity.ok(data);
    }

    /** 按「每卷 N 章」重建卷序列与全部绑定。 */
    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable Long projectId, @RequestBody(required = false) RebuildRequest req) {
        try {
            volumeService.rebuildBindings(projectId, req == null ? null : req.chaptersPerVolume());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 把若干章节移动到指定分卷（单章移动、批量扩卷/缩卷都走这里）。 */
    @PostMapping("/{volumeId}/chapters")
    public ResponseEntity<?> assign(@PathVariable Long projectId,
                                    @PathVariable Long volumeId,
                                    @RequestBody AssignRequest req) {
        try {
            List<Integer> numbers = (req == null || req.chapterNumbers() == null) ? List.of() : req.chapterNumbers();
            volumeService.assignChapters(projectId, volumeId, numbers);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 末尾新增一个空分卷。 */
    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long projectId,
                                    @RequestBody(required = false) CreateVolumeRequest req) {
        try {
            Long id = volumeService.createVolume(projectId, req == null ? null : req.title());
            return ResponseEntity.ok(Map.of("status", "ok", "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 分卷改名。 */
    @PutMapping("/{volumeId}")
    public ResponseEntity<?> rename(@PathVariable Long projectId,
                                    @PathVariable Long volumeId,
                                    @RequestBody(required = false) RenameVolumeRequest req) {
        try {
            volumeService.renameVolume(projectId, volumeId, req == null ? null : req.title());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除分卷（名下章节先迁到相邻分卷）。 */
    @DeleteMapping("/{volumeId}")
    public ResponseEntity<?> delete(@PathVariable Long projectId, @PathVariable Long volumeId) {
        try {
            volumeService.deleteVolume(projectId, volumeId);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
