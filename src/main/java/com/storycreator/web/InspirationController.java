package com.storycreator.web;

import com.storycreator.persistence.entity.InspirationEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.InspirationRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 灵感：项目维度的灵感片段管理（列表 / 新增 / 详情 / 编辑 / 删除）。
 * <p>页面是独立页面，从项目信息页与创作页以新标签页方式打开。
 */
@Controller
@RequestMapping("/projects/{projectId}/inspirations")
public class InspirationController {

    private final InspirationRepository inspirationRepository;
    private final ProjectRepository projectRepository;

    public InspirationController(InspirationRepository inspirationRepository,
                                 ProjectRepository projectRepository) {
        this.inspirationRepository = inspirationRepository;
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public String list(@PathVariable Long projectId, Model model) {
        ProjectEntity project = requireProject(projectId);
        model.addAttribute("project", project);
        model.addAttribute("inspirations", inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId));
        return "inspirations";
    }

    @PostMapping
    public String create(@PathVariable Long projectId,
                         @RequestParam(required = false) String title,
                         @RequestParam(defaultValue = "") String content,
                         RedirectAttributes redirectAttributes) {
        requireProject(projectId);
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "标题不能为空");
            return "redirect:/projects/" + projectId + "/inspirations";
        }
        InspirationEntity entity = new InspirationEntity();
        entity.setProjectId(projectId);
        entity.setTitle(trimmed);
        entity.setContent(content);
        InspirationEntity saved = inspirationRepository.save(entity);
        redirectAttributes.addFlashAttribute("message", "灵感已保存");
        return "redirect:/projects/" + projectId + "/inspirations/" + saved.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long projectId, @PathVariable Long id, Model model) {
        ProjectEntity project = requireProject(projectId);
        model.addAttribute("project", project);
        model.addAttribute("inspiration", requireInspiration(projectId, id));
        return "inspiration-detail";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long projectId, @PathVariable Long id, Model model) {
        ProjectEntity project = requireProject(projectId);
        model.addAttribute("project", project);
        model.addAttribute("inspiration", requireInspiration(projectId, id));
        return "inspiration-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long projectId,
                         @PathVariable Long id,
                         @RequestParam(required = false) String title,
                         @RequestParam(defaultValue = "") String content,
                         RedirectAttributes redirectAttributes) {
        requireProject(projectId);
        InspirationEntity entity = requireInspiration(projectId, id);
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "标题不能为空");
            return "redirect:/projects/" + projectId + "/inspirations/" + id + "/edit";
        }
        entity.setTitle(trimmed);
        entity.setContent(content);
        inspirationRepository.save(entity);
        redirectAttributes.addFlashAttribute("message", "灵感已更新");
        return "redirect:/projects/" + projectId + "/inspirations/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long projectId, @PathVariable Long id,
                         RedirectAttributes redirectAttributes) {
        requireProject(projectId);
        InspirationEntity entity = requireInspiration(projectId, id);
        inspirationRepository.delete(entity);
        redirectAttributes.addFlashAttribute("message", "灵感已删除");
        return "redirect:/projects/" + projectId + "/inspirations";
    }

    private ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    /** 灵感必须属于该项目，避免跨项目越权访问。 */
    private InspirationEntity requireInspiration(Long projectId, Long id) {
        return inspirationRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inspiration not found: " + id + " (project " + projectId + ")"));
    }
}
