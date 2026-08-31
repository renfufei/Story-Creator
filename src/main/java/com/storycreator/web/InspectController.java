package com.storycreator.web;

import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequestMapping("/projects/{projectId}/inspect")
public class InspectController {

    private final ProjectRepository projectRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final CharacterRepository characterRepository;
    private final VolumeOutlineRepository volumeOutlineRepository;
    private final StoryOutlineRepository storyOutlineRepository;

    public InspectController(ProjectRepository projectRepository,
                            ChapterRepository chapterRepository,
                            ChapterOutlineRepository chapterOutlineRepository,
                            CharacterRepository characterRepository,
                            VolumeOutlineRepository volumeOutlineRepository,
                            StoryOutlineRepository storyOutlineRepository) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.characterRepository = characterRepository;
        this.volumeOutlineRepository = volumeOutlineRepository;
        this.storyOutlineRepository = storyOutlineRepository;
    }

    @GetMapping
    public String inspectOverview(@PathVariable Long projectId, Model model) {
        var project = projectRepository.findById(projectId).orElseThrow();
        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        var outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
        var volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        var storyOutline = storyOutlineRepository.findByProjectId(projectId).map(StoryOutlineEntity::getContent).orElse(null);

        // Build chapter completeness data
        Map<Integer, String> outlineEventPlans = new HashMap<>();
        for (var o : outlines) {
            if (o.getEventPlan() != null && !o.getEventPlan().isBlank()) {
                outlineEventPlans.put(o.getChapterNumber(), "Y");
            }
        }

        List<Map<String, Object>> chapterList = new ArrayList<>();
        for (var ch : chapters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", ch.getChapterNumber());
            item.put("title", ch.getTitle());
            item.put("hasContent", hasText(ch.getContent()));
            item.put("hasContentDraft", hasText(ch.getContentDraft()));
            item.put("hasWritingBriefing", hasText(ch.getWritingBriefing()));
            item.put("hasContentSummary", hasText(ch.getContentSummary()));
            item.put("hasCharacterStates", hasText(ch.getCharacterStates()));
            item.put("hasEventPlan", outlineEventPlans.containsKey(ch.getChapterNumber()));
            chapterList.add(item);
        }

        model.addAttribute("project", project);
        model.addAttribute("storyOutline", storyOutline);
        model.addAttribute("volumes", volumes);
        model.addAttribute("outlines", outlines);
        model.addAttribute("chapterList", chapterList);
        return "inspect";
    }

    @GetMapping("/chapters/{num}")
    public String inspectChapter(@PathVariable Long projectId, @PathVariable int num, Model model) {
        var project = projectRepository.findById(projectId).orElseThrow();
        var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        var outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
        var volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);

        var chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);
        var outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);

        // Build field availability map for current chapter
        Map<String, Boolean> fieldAvail = new LinkedHashMap<>();
        fieldAvail.put("outlineSummary", outline != null && hasText(outline.getSummary()));
        fieldAvail.put("writingBriefing", chapter != null && hasText(chapter.getWritingBriefing()));
        fieldAvail.put("eventPlan", outline != null && hasText(outline.getEventPlan()));
        fieldAvail.put("content", chapter != null && hasText(chapter.getContent()));
        fieldAvail.put("contentDraft", chapter != null && hasText(chapter.getContentDraft()));
        fieldAvail.put("contentSummary", chapter != null && hasText(chapter.getContentSummary()));
        fieldAvail.put("characterStates", chapter != null && hasText(chapter.getCharacterStates()));

        // Lightweight chapter list for sidebar (show volume title only for first chapter in each volume)
        List<Map<String, Object>> chapterMetas = new ArrayList<>();
        int lastVolume = -1;
        for (var ch : chapters) {
            Map<String, Object> m = new HashMap<>();
            m.put("number", ch.getChapterNumber());
            m.put("title", ch.getTitle());
            for (var v : volumes) {
                if (ch.getChapterNumber() >= v.getChapterStart() && ch.getChapterNumber() <= v.getChapterEnd()) {
                    if (v.getVolumeNumber() != lastVolume) {
                        m.put("volumeTitle", v.getTitle());
                        lastVolume = v.getVolumeNumber();
                    }
                    break;
                }
            }
            chapterMetas.add(m);
        }

        int maxNum = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNumber();

        model.addAttribute("project", project);
        model.addAttribute("chapterNum", num);
        model.addAttribute("chapterTitle", chapter != null ? chapter.getTitle() : "第" + num + "章");
        model.addAttribute("fieldAvail", fieldAvail);
        model.addAttribute("chapterMetas", chapterMetas);
        model.addAttribute("prevNum", num > 1 ? num - 1 : null);
        model.addAttribute("nextNum", num < maxNum ? num + 1 : null);
        model.addAttribute("volumes", volumes);
        return "inspect-chapter";
    }

    @GetMapping("/characters")
    public String inspectCharacters(@PathVariable Long projectId, Model model) {
        var project = projectRepository.findById(projectId).orElseThrow();
        var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);

        model.addAttribute("project", project);
        model.addAttribute("characters", characters);
        return "inspect-characters";
    }

    // === AJAX endpoint: load single chapter field content ===
    @GetMapping("/chapters/{num}/field/{fieldName}")
    @ResponseBody
    public Map<String, String> getChapterField(@PathVariable Long projectId,
                                               @PathVariable int num,
                                               @PathVariable String fieldName) {
        var chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);

        String content = null;
        String type = "text";

        switch (fieldName) {
            case "writingBriefing" -> content = chapter != null ? chapter.getWritingBriefing() : null;
            case "content" -> content = chapter != null ? chapter.getContent() : null;
            case "contentDraft" -> content = chapter != null ? chapter.getContentDraft() : null;
            case "contentSummary" -> content = chapter != null ? chapter.getContentSummary() : null;
            case "characterStates" -> {
                content = chapter != null ? chapter.getCharacterStates() : null;
                if (content != null && content.trim().startsWith("[")) {
                    type = "json";
                }
            }
            case "eventPlan" -> {
                var outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);
                content = outline != null ? outline.getEventPlan() : null;
            }
            case "outlineSummary" -> {
                var outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);
                content = outline != null ? outline.getSummary() : null;
            }
        }

        Map<String, String> result = new HashMap<>();
        result.put("content", content != null ? content : "");
        result.put("type", type);
        return result;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
