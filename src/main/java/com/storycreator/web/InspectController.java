package com.storycreator.web;

import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
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

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> inspectOverview(@PathVariable Long projectId) {
        return projectRepository.findById(projectId).map(project -> {
            var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
            var outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
            var volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
            var storyOutline = storyOutlineRepository.findByProjectId(projectId).map(StoryOutlineEntity::getContent).orElse(null);

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

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", projectId);
            out.put("projectTitle", project.getTitle());
            out.put("writingRules", null);
            out.put("styleFingerprint", null);
            out.put("storyOutline", storyOutline);
            out.put("volumes", volumes.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("volumeNumber", v.getVolumeNumber());
                m.put("title", v.getTitle());
                m.put("chapterStart", v.getChapterStart());
                m.put("chapterEnd", v.getChapterEnd());
                m.put("arcSummary", v.getArcSummary());
                return m;
            }).toList());
            out.put("outlines", outlines.stream().map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chapterNumber", o.getChapterNumber());
                m.put("title", o.getTitle());
                m.put("characterNames", o.getCharacterNames());
                m.put("summary", o.getSummary());
                return m;
            }).toList());
            out.put("chapterList", chapterList);
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/chapters/{num}/data")
    public ResponseEntity<Map<String, Object>> inspectChapterData(@PathVariable Long projectId, @PathVariable int num) {
        return projectRepository.findById(projectId).map(project -> {
            var chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
            var outlines = chapterOutlineRepository.findByProjectIdOrderByChapterNumber(projectId);
            var volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
            var chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);
            var outline = chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, num).orElse(null);

            Map<String, Boolean> fieldAvail = new LinkedHashMap<>();
            fieldAvail.put("outlineSummary", outline != null && hasText(outline.getSummary()));
            fieldAvail.put("writingBriefing", chapter != null && hasText(chapter.getWritingBriefing()));
            fieldAvail.put("eventPlan", outline != null && hasText(outline.getEventPlan()));
            fieldAvail.put("content", chapter != null && hasText(chapter.getContent()));
            fieldAvail.put("contentDraft", chapter != null && hasText(chapter.getContentDraft()));
            fieldAvail.put("contentSummary", chapter != null && hasText(chapter.getContentSummary()));
            fieldAvail.put("characterStates", chapter != null && hasText(chapter.getCharacterStates()));

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

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", projectId);
            out.put("projectTitle", project.getTitle());
            out.put("chapterNum", num);
            out.put("chapterTitle", chapter != null ? chapter.getTitle() : "第" + num + "章");
            out.put("fieldAvail", fieldAvail);
            out.put("chapterMetas", chapterMetas);
            out.put("prevNum", num > 1 ? num - 1 : null);
            out.put("nextNum", num < maxNum ? num + 1 : null);
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/characters/data")
    public ResponseEntity<Map<String, Object>> inspectCharactersData(@PathVariable Long projectId) {
        return projectRepository.findById(projectId).map(project -> {
            var characters = characterRepository.findByProjectIdOrderBySortOrder(projectId);
            List<Map<String, Object>> list = characters.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("role", c.getRole());
                m.put("gender", c.getGender());
                m.put("personality", c.getPersonality());
                m.put("appearance", c.getAppearance());
                m.put("motivation", c.getMotivation());
                m.put("relationships", c.getRelationships());
                m.put("abilities", c.getAbilities());
                m.put("background", c.getBackground());
                return m;
            }).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projectId", projectId);
            out.put("projectTitle", project.getTitle());
            out.put("characters", list);
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // === AJAX endpoint: load single chapter field content ===
    @GetMapping("/chapters/{num}/field/{fieldName}")
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
