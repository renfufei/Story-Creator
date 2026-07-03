package com.storycreator.web;

import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.core.domain.ModelType;
import com.storycreator.persistence.entity.MaterialLibraryEntity;
import com.storycreator.persistence.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/settings/materials")
public class MaterialLibraryController {

    private static final Logger log = LoggerFactory.getLogger(MaterialLibraryController.class);

    private final MaterialLibraryService materialLibraryService;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ProjectRepository projectRepository;
    private final AiModelConfigRepository aiModelConfigRepository;

    public MaterialLibraryController(MaterialLibraryService materialLibraryService,
                                     WorldSettingRepository worldSettingRepository,
                                     CharacterRepository characterRepository,
                                     ChapterOutlineRepository chapterOutlineRepository,
                                     ChapterRepository chapterRepository,
                                     StoryOutlineRepository storyOutlineRepository,
                                     ProjectRepository projectRepository,
                                     AiModelConfigRepository aiModelConfigRepository) {
        this.materialLibraryService = materialLibraryService;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.projectRepository = projectRepository;
        this.aiModelConfigRepository = aiModelConfigRepository;
    }

    @GetMapping
    public String listPage(Model model) {
        model.addAttribute("items", materialLibraryService.findAll());
        model.addAttribute("categories", MaterialCategory.values());
        model.addAttribute("modelConfigs", aiModelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT));
        return "materials";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam MaterialCategory category,
                         @RequestParam(defaultValue = "") String content,
                         @RequestParam(required = false) String sourceHint) {
        MaterialLibraryEntity entity = new MaterialLibraryEntity();
        entity.setName(name);
        entity.setCategory(category);
        entity.setContent(content);
        entity.setSourceHint(sourceHint);
        materialLibraryService.save(entity);
        return "redirect:/settings/materials";
    }

    @GetMapping("/{id}/edit")
    public String editPage(@PathVariable Long id, Model model) {
        MaterialLibraryEntity entity = materialLibraryService.findById(id);
        if (entity == null) throw new IllegalArgumentException("Material not found: " + id);
        model.addAttribute("item", entity);
        model.addAttribute("categories", MaterialCategory.values());
        return "material-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam MaterialCategory category,
                         @RequestParam(defaultValue = "") String content,
                         @RequestParam(required = false) String sourceHint) {
        MaterialLibraryEntity entity = materialLibraryService.findById(id);
        if (entity == null) throw new IllegalArgumentException("Material not found: " + id);
        entity.setName(name);
        entity.setCategory(category);
        entity.setContent(content);
        entity.setSourceHint(sourceHint);
        materialLibraryService.save(entity);
        return "redirect:/settings/materials";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        materialLibraryService.deleteById(id);
        return "redirect:/settings/materials";
    }

    @PostMapping("/distill")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> distill(@RequestParam Long projectId,
                                                        @RequestParam String sourceType,
                                                        @RequestParam MaterialCategory category,
                                                        @RequestParam(required = false) String name,
                                                        @RequestParam(required = false) Long configId) {
        String rawContent = resolveSourceContent(projectId, sourceType);
        if (rawContent == null || rawContent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "源内容为空，无法蒸馏"));
        }

        String projectTitle = projectRepository.findById(projectId)
                .map(p -> p.getTitle())
                .orElse("未知项目");

        if (name == null || name.isBlank()) {
            name = projectTitle + "-" + category.getDisplayName();
        }
        String sourceHint = projectTitle + " / " + sourceType;

        try {
            MaterialLibraryEntity saved = materialLibraryService.distill(projectId, rawContent, category, name, sourceHint, configId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", saved.getId(),
                    "name", saved.getName(),
                    "content", saved.getContent()
            ));
        } catch (Exception e) {
            log.error("Distillation failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "蒸馏失败: " + e.getMessage()));
        }
    }

    @GetMapping("/list-json")
    @ResponseBody
    public List<Map<String, Object>> listJson(@RequestParam(required = false) MaterialCategory category) {
        List<MaterialLibraryEntity> items;
        if (category != null) {
            items = materialLibraryService.findByCategory(category);
        } else {
            items = materialLibraryService.findAll();
        }
        return items.stream().map(item -> Map.<String, Object>of(
                "id", item.getId(),
                "name", item.getName(),
                "category", item.getCategory().name(),
                "categoryLabel", item.getCategory().getDisplayName(),
                "content", item.getContent() != null ? item.getContent() : "",
                "sourceHint", item.getSourceHint() != null ? item.getSourceHint() : ""
        )).toList();
    }

    @GetMapping("/projects-json")
    @ResponseBody
    public List<Map<String, Object>> projectsJson() {
        return projectRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "title", p.getTitle()))
                .toList();
    }

    @GetMapping("/project-sources")
    @ResponseBody
    public Map<String, Object> projectSources(@RequestParam Long projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasWorld", worldSettingRepository.findByProjectId(projectId).isPresent());
        result.put("hasOutline", storyOutlineRepository.findByProjectId(projectId).isPresent());

        var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId).stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList();
        result.put("characters", characters);

        var chapterOutlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .map(co -> Map.<String, Object>of("num", co.getChapterNumber(),
                        "title", co.getTitle() != null ? co.getTitle() : "第" + co.getChapterNumber() + "章"))
                .toList();
        result.put("chapterOutlines", chapterOutlines);

        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .filter(ch -> ch.getContent() != null && !ch.getContent().isBlank())
                .map(ch -> Map.<String, Object>of("num", ch.getChapterNumber(),
                        "title", ch.getTitle() != null ? ch.getTitle() : "第" + ch.getChapterNumber() + "章"))
                .toList();
        result.put("chapters", chapters);

        return result;
    }

    private String resolveSourceContent(Long projectId, String sourceType) {
        if (sourceType.equals("world")) {
            return worldSettingRepository.findByProjectId(projectId)
                    .map(ws -> ws.getContent())
                    .orElse(null);
        }
        if (sourceType.startsWith("character:")) {
            Long charId = Long.parseLong(sourceType.substring("character:".length()));
            return characterRepository.findById(charId)
                    .map(c -> c.getContent())
                    .orElse(null);
        }
        if (sourceType.equals("outline")) {
            return storyOutlineRepository.findByProjectId(projectId)
                    .map(o -> o.getContent())
                    .orElse(null);
        }
        if (sourceType.startsWith("chapter_outline:")) {
            int chapterNum = Integer.parseInt(sourceType.substring("chapter_outline:".length()));
            return chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                    .map(co -> co.getTitle() + "\n" + co.getSummary())
                    .orElse(null);
        }
        if (sourceType.startsWith("chapter:")) {
            int chapterNum = Integer.parseInt(sourceType.substring("chapter:".length()));
            return chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNum)
                    .map(c -> c.getContent())
                    .orElse(null);
        }
        return null;
    }
}
