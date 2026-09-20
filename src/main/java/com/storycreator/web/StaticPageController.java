package com.storycreator.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 静态页路由：URL -> {@code classpath:/static/pages/*.html}。
 *
 * <p>前端正从 Thymeleaf 迁移到「静态 HTML + JS + Ajax」，页面本身不再由模板引擎渲染，
 * 但要保持原有 URL 不变（书签、其它页面的链接都还指向旧地址），因此这里统一做 forward。
 *
 * <p><b>为什么不用 {@code WebMvcConfigurer#addViewControllers}？</b>
 * 只要同一路径上还存在任意 {@code @XxxMapping}（哪怕方法不同），
 * {@code RequestMappingHandlerMapping} 会先按路径命中、再因 HTTP 方法不匹配抛 405，
 * 而它优先级高于 view-controller 的 {@code SimpleUrlHandlerMapping}，导致 forward 永远不生效。
 * 用显式 {@code @GetMapping} 才能稳定命中。
 *
 * <p>每迁移完一个页面：把它的 URL 登记到这里，并删除对应 {@code templates/*.html} 与 Controller 里的 view 映射。
 */
@Controller
public class StaticPageController {

    @GetMapping("/")
    public String dashboard() {
        return "forward:/pages/dashboard.html";
    }

    @GetMapping("/projects/new")
    public String projectNew() {
        return "forward:/pages/project-form.html";
    }

    @GetMapping("/projects/{id}")
    public String projectDetail() {
        return "forward:/pages/project-detail.html";
    }

    @GetMapping("/projects/{id}/edit")
    public String projectEdit() {
        return "forward:/pages/project-form.html";
    }

    @GetMapping("/projects/{projectId}/inspirations")
    public String inspirationsList() {
        return "forward:/pages/inspirations/list.html";
    }

    @GetMapping("/projects/{projectId}/inspirations/{inspId}")
    public String inspirationDetail() {
        return "forward:/pages/inspirations/detail.html";
    }

    @GetMapping("/projects/{projectId}/inspirations/{inspId}/edit")
    public String inspirationEdit() {
        return "forward:/pages/inspirations/edit.html";
    }

    /** 跨项目灵感汇总页（按项目分组）。 */
    @GetMapping("/inspirations")
    public String allInspirations() {
        return "forward:/pages/inspirations/all.html";
    }

    @GetMapping("/settings")
    public String settings() {
        return "forward:/pages/settings.html";
    }

    /** 工作流步骤名 -> 前端路由段（与 workflow 拆分后的页面目录、core.js stepList.route 保持一致）。 */
    private static final java.util.Map<String, String> WORKFLOW_STEP_ROUTE = java.util.Map.of(
            "WORLD_BUILDING", "world-building",
            "CHARACTER_DESIGN", "characters",
            "OUTLINE_GENERATION", "outline",
            "CHAPTER_WRITING", "chapters",
            "POLISHING", "polishing",
            "PROOFREADING", "proofreading"
    );

    /**
     * 工作流入口：Hub 选择页已下线，无 step 参数时直接重定向到第一步（世界观设定）。
     * 兼容旧链接 {@code /projects/{projectId}/workflow?step=X}，重定向到对应的按步骤页面。
     */
    @GetMapping("/projects/{projectId}/workflow")
    public String workflow(@PathVariable Long projectId,
                           @RequestParam(required = false) String step) {
        String route = (step != null && WORKFLOW_STEP_ROUTE.containsKey(step))
                ? WORKFLOW_STEP_ROUTE.get(step)
                : WORKFLOW_STEP_ROUTE.get("WORLD_BUILDING");
        return "redirect:/projects/" + projectId + "/workflow/" + route;
    }

    /** 按步骤拆分后的独立工作流页面（world-building/characters/outline/chapters/polishing/proofreading）。 */
    @GetMapping("/projects/{projectId}/workflow/{step:world-building|characters|outline|chapters|polishing|proofreading}")
    public String workflowStep(@PathVariable Long projectId, @PathVariable String step) {
        return "forward:/pages/workflow/" + step + ".html";
    }

    @GetMapping("/projects/{id}/read")
    public String reader() {
        return "forward:/pages/reader.html";
    }

    @GetMapping("/projects/{projectId}/side-stories")
    public String sideStoryList() {
        return "forward:/pages/side-story-list.html";
    }

    @GetMapping("/projects/{projectId}/side-stories/{id}")
    public String sideStoryDetail() {
        return "forward:/pages/side-story.html";
    }

    @GetMapping("/projects/{projectId}/expansion")
    public String expansion() {
        return "forward:/pages/expansion.html";
    }

    @GetMapping("/projects/{projectId}/inspect")
    public String inspectOverview() {
        return "forward:/pages/inspect.html";
    }

    @GetMapping("/projects/{projectId}/inspect/chapters/{num}")
    public String inspectChapter() {
        return "forward:/pages/inspect-chapter.html";
    }

    @GetMapping("/projects/{projectId}/inspect/characters")
    public String inspectCharacters() {
        return "forward:/pages/inspect-characters.html";
    }

    /**
     * 分卷管理页：手工调整「章节 ↔ 分卷」归属。
     *
     * <p>注意 {@code /projects/{projectId}/volumes} 已被 {@link WorkflowController} 占用（返回卷元数据 JSON），
     * 故页面走 {@code /volumes/manage}，避免 Ambiguous mapping。
     */
    @GetMapping("/projects/{projectId}/volumes/manage")
    public String volumeManager() {
        return "forward:/pages/volumes.html";
    }
}
