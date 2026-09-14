package com.storycreator.api;

import com.storycreator.persistence.entity.InspirationEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.InspirationRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 跨项目灵感汇总：按项目分组返回全部灵感，供「所有灵感」页面（{@code /inspirations} -> all.html）使用。
 * 与 {@link InspirationApiController}（单项目作用域）区分：本控制器无 projectId 路径变量，挂在 {@code /api/inspirations} 下。
 */
@RestController
@RequestMapping("/api/inspirations")
public class AllInspirationsApiController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InspirationRepository inspirationRepository;
    private final ProjectRepository projectRepository;

    public AllInspirationsApiController(InspirationRepository inspirationRepository,
                                        ProjectRepository projectRepository) {
        this.inspirationRepository = inspirationRepository;
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public List<ProjectGroup> all() {
        // 全部灵感，按创建时间倒序（同毫秒靠 id 兜底，保证稳定顺序）
        List<InspirationEntity> all = new ArrayList<>(inspirationRepository.findAll());
        all.sort((a, b) -> {
            int c = b.getCreatedAt().compareTo(a.getCreatedAt());
            return c != 0 ? c : Long.compare(b.getId(), a.getId());
        });

        Map<Long, String> titles = projectRepository.findAll().stream()
                .collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getTitle, (x, y) -> x));

        Map<Long, List<InspirationEntity>> byProject = all.stream()
                .collect(Collectors.groupingBy(InspirationEntity::getProjectId));

        List<ProjectGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<InspirationEntity>> e : byProject.entrySet()) {
            Long pid = e.getKey();
            List<Summary> items = e.getValue().stream().map(Summary::from).toList();
            groups.add(new ProjectGroup(pid, titles.getOrDefault(pid, "项目 " + pid), items.size(), items));
        }

        // 项目按「最新灵感时间」倒序（组内已倒序，取首条即可；格式 yyyy-MM-dd HH:mm 可直接字符串比较）
        groups.sort((g1, g2) -> {
            String t1 = g1.items().isEmpty() ? "" : g1.items().get(0).createdAt();
            String t2 = g2.items().isEmpty() ? "" : g2.items().get(0).createdAt();
            return t2.compareTo(t1);
        });
        return groups;
    }

    public record Summary(Long id, Long projectId, String title, String contentPreview, String createdAt) {
        static Summary from(InspirationEntity e) {
            return new Summary(e.getId(), e.getProjectId(), e.getTitle(),
                    e.getContentPreview(), fmt(e.getCreatedAt()));
        }

        static String fmt(LocalDateTime t) {
            return t == null ? "" : t.format(FMT);
        }
    }

    public record ProjectGroup(Long projectId, String projectTitle, int count, List<Summary> items) {
    }
}
