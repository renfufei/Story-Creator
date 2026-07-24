package com.storycreator.web;

import com.storycreator.persistence.entity.SideStoryEntity;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.SideStoryChapterRepository;
import com.storycreator.persistence.repository.SideStoryRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import com.storycreator.sidestory.SideStoryWorkflowService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
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

    @GetMapping
    public String list(@PathVariable Long projectId, Model model) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        var sideStories = sideStoryRepository.findByProjectIdOrderBySortOrder(projectId);

        // Attach chapter counts
        var storiesWithCounts = sideStories.stream().map(ss -> {
            int count = sideStoryChapterRepository.countBySideStoryId(ss.getId());
            return java.util.Map.of("story", ss, "chapterCount", count);
        }).toList();

        model.addAttribute("project", project);
        model.addAttribute("sideStories", storiesWithCounts);
        model.addAttribute("characters", characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0));
        model.addAttribute("volumes", volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId));
        return "side-story-list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long projectId, @PathVariable Long id, Model model) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        var sideStory = sideStoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Side story not found: " + id));
        var chapters = sideStoryChapterRepository.findBySideStoryIdOrderByChapterNumber(id);
        var characterIds = workflowService.getCharacterIds(id);
        var characters = characterRepository.findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);

        model.addAttribute("project", project);
        model.addAttribute("sideStory", sideStory);
        model.addAttribute("chapters", chapters);
        model.addAttribute("characterIds", characterIds);
        model.addAttribute("characters", characters);
        model.addAttribute("volumes", volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId));
        return "side-story";
    }
}
