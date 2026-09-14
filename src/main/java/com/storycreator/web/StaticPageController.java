package com.storycreator.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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

    @GetMapping("/settings")
    public String settings() {
        return "forward:/pages/settings.html";
    }

    @GetMapping("/projects/{projectId}/workflow")
    public String workflow() {
        return "forward:/pages/workflow.html";
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
}
