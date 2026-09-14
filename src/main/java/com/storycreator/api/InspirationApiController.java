package com.storycreator.api;

import com.storycreator.persistence.entity.InspirationEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.InspirationRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 灵感管理 JSON API（页面迁移到「静态 HTML + JS + Ajax」后，列表/详情/新增/编辑/删除都走这里）。
 * 页面见 {@code static/pages/inspirations/}，路由见 {@link com.storycreator.web.StaticPageController}。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/inspirations")
public class InspirationApiController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InspirationRepository inspirationRepository;
    private final ProjectRepository projectRepository;

    public InspirationApiController(InspirationRepository inspirationRepository,
                                   ProjectRepository projectRepository) {
        this.inspirationRepository = inspirationRepository;
        this.projectRepository = projectRepository;
    }

    /** 列表：按创建时间倒序（同毫秒靠 id 兜底，保证稳定顺序）。 */
    @GetMapping
    public List<InspirationDto> list(@PathVariable Long projectId) {
        requireProject(projectId);
        return inspirationRepository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .stream().map(InspirationDto::from).toList();
    }

    @GetMapping("/{id}")
    public InspirationDto detail(@PathVariable Long projectId, @PathVariable Long id) {
        return InspirationDto.from(requireInspiration(projectId, id));
    }

    @PostMapping
    public InspirationDto create(@PathVariable Long projectId,
                                @RequestBody Map<String, String> body) {
        requireProject(projectId);
        String title = body == null ? "" : trim(body.get("title"));
        String content = body == null ? "" : (body.get("content") == null ? "" : body.get("content"));
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        InspirationEntity entity = new InspirationEntity();
        entity.setProjectId(projectId);
        entity.setTitle(title);
        entity.setContent(content);
        return InspirationDto.from(inspirationRepository.save(entity));
    }

    @PutMapping("/{id}")
    public InspirationDto update(@PathVariable Long projectId, @PathVariable Long id,
                                @RequestBody Map<String, String> body) {
        InspirationEntity entity = requireInspiration(projectId, id);
        String title = trim(body.get("title"));
        String content = body.get("content") == null ? "" : body.get("content");
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        entity.setTitle(title);
        entity.setContent(content);
        return InspirationDto.from(inspirationRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long projectId, @PathVariable Long id) {
        inspirationRepository.delete(requireInspiration(projectId, id));
        return Map.of("status", "ok");
    }

    private ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Project not found: " + projectId));
    }

    /** 灵感必须属于该项目，避免跨项目越权访问。 */
    private InspirationEntity requireInspiration(Long projectId, Long id) {
        return inspirationRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Inspiration not found: " + id + " (project " + projectId + ")"));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public record InspirationDto(
            Long id,
            Long projectId,
            String title,
            String content,
            String contentPreview,
            String createdAt,
            String updatedAt
    ) {
        static InspirationDto from(InspirationEntity e) {
            return new InspirationDto(
                    e.getId(),
                    e.getProjectId(),
                    e.getTitle(),
                    e.getContent(),
                    e.getContentPreview(),
                    fmt(e.getCreatedAt()),
                    fmt(e.getUpdatedAt())
            );
        }

        static String fmt(LocalDateTime t) {
            return t == null ? "" : t.format(FMT);
        }
    }
}
