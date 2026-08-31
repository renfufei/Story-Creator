package com.storycreator.workflow.engine;

import com.storycreator.core.domain.WorldFacetKey;
import com.storycreator.persistence.entity.*;
import com.storycreator.persistence.repository.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.storycreator.workflow.engine.TextProcessingUtils.truncate;

@Service
public class WorkflowContextBuilder {

    private final ProjectRepository projectRepository;
    private final WorldSettingRepository worldSettingRepository;
    private final CharacterRepository characterRepository;
    private final StoryOutlineRepository storyOutlineRepository;
    private final ChapterOutlineRepository chapterOutlineRepository;
    private final ChapterRepository chapterRepository;
    private final StepGuidanceRepository stepGuidanceRepository;
    private final WorldSettingFacetRepository worldSettingFacetRepository;

    public WorkflowContextBuilder(ProjectRepository projectRepository,
                                  WorldSettingRepository worldSettingRepository,
                                  CharacterRepository characterRepository,
                                  StoryOutlineRepository storyOutlineRepository,
                                  ChapterOutlineRepository chapterOutlineRepository,
                                  ChapterRepository chapterRepository,
                                  StepGuidanceRepository stepGuidanceRepository,
                                  WorldSettingFacetRepository worldSettingFacetRepository) {
        this.projectRepository = projectRepository;
        this.worldSettingRepository = worldSettingRepository;
        this.characterRepository = characterRepository;
        this.storyOutlineRepository = storyOutlineRepository;
        this.chapterOutlineRepository = chapterOutlineRepository;
        this.chapterRepository = chapterRepository;
        this.stepGuidanceRepository = stepGuidanceRepository;
        this.worldSettingFacetRepository = worldSettingFacetRepository;
    }

    public WorkflowContext build(Long projectId) {
        return build(projectId, 0);
    }

    public WorkflowContext build(Long projectId, int chapterNumber) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        WorkflowContext context = new WorkflowContext();
        context.setProjectId(projectId);
        context.setTitle(project.getTitle());
        context.setGenre(project.getGenre());
        context.setDescription(project.getDescription());
        context.setCurrentStep(project.getCurrentStep());
        context.setTotalChapters(project.getTotalChapters());
        context.setChapterWordCount(project.getChapterWordCount());
        context.setChapterWordCountMin(project.getChapterWordCountMin());
        context.setChapterWordCountMax(project.getChapterWordCountMax());

        // Load world setting (prefer WORLD_BACKGROUND facet for chapter writing, fallback to summary)
        worldSettingRepository.findByProjectId(projectId)
                .ifPresent(ws -> {
                    if (chapterNumber > 0) {
                        String facetContent = worldSettingFacetRepository
                                .findByProjectIdAndFacetKey(projectId, WorldFacetKey.WORLD_BACKGROUND)
                                .map(WorldSettingFacetEntity::getContent)
                                .filter(c -> !c.isBlank())
                                .orElse(null);
                        if (facetContent != null) {
                            context.setWorldSetting(facetContent);
                        } else if (ws.getSummary() != null && !ws.getSummary().isBlank()) {
                            context.setWorldSetting(ws.getSummary());
                        } else {
                            context.setWorldSetting(ws.getContent());
                        }
                    } else {
                        context.setWorldSetting(ws.getContent());
                    }
                });

        // Load characters - overview (sort_order=0) goes to characters field
        List<CharacterEntity> chars = characterRepository.findByProjectIdOrderBySortOrder(projectId);

        // Determine current volume number for filtering (only when writing a specific chapter)
        final int currentVolume;
        if (chapterNumber > 0 && project.getChaptersPerVolume() > 0) {
            currentVolume = (chapterNumber - 1) / project.getChaptersPerVolume() + 1;
        } else {
            currentVolume = 0;
        }

        if (!chars.isEmpty()) {
            chars.stream().filter(c -> c.getSortOrder() == 0).findFirst()
                    .ifPresent(s -> {
                        if (chapterNumber > 0 && s.getSummary() != null && !s.getSummary().isBlank()) {
                            context.setCharacters(s.getSummary());
                        } else {
                            context.setCharacters(s.getContent());
                        }
                    });
            // Fallback: if no sort_order=0 entry, concat all (backward compat)
            if (context.getCharacters() == null) {
                StringBuilder sb = new StringBuilder();
                for (CharacterEntity c : chars) {
                    if (c.getContent() != null) sb.append(c.getContent()).append("\n\n");
                }
                context.setCharacters(sb.toString().trim());
            }
        }

        // Load outline
        storyOutlineRepository.findByProjectId(projectId).ifPresent(o -> {
            String fullContent = o.getContent();
            context.setOutline(fullContent);
            context.setStorySummary(fullContent);

            if (fullContent != null) {
                context.setOverallOutline(truncate(fullContent, 800));
            }
        });

        // Load chapter info
        if (chapterNumber > 0) {
            context.setCurrentChapter(chapterNumber);
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                    .ifPresent(co -> {
                        context.setChapterTitle(co.getTitle());
                        String summary = co.getSummary();
                        if (co.getEventPlan() != null && !co.getEventPlan().isBlank()) {
                            summary = summary + "\n\n【事件计划】\n" + co.getEventPlan();
                        }
                        context.setChapterSummary(summary);

                        // Load character cards for this chapter's characters (filtered by volume)
                        if (co.getCharacterNames() != null && !co.getCharacterNames().isBlank()) {
                            List<String> names = List.of(co.getCharacterNames().split("[,，、]"));
                            List<CharacterEntity> cards = characterRepository
                                    .findByProjectIdAndSortOrderGreaterThanOrderBySortOrder(projectId, 0);
                            StringBuilder cardSb = new StringBuilder();
                            for (CharacterEntity card : cards) {
                                // Volume filtering: skip characters not active in current volume
                                if (currentVolume > 0 && !isCharacterActiveInVolume(card, currentVolume)) {
                                    continue;
                                }
                                boolean matched = names.stream()
                                        .anyMatch(n -> n.trim().equals(card.getName()));
                                if (matched && card.getContent() != null) {
                                    String cardText;
                                    if (card.getSummary() != null && !card.getSummary().isBlank()) {
                                        cardText = card.getSummary();
                                    } else {
                                        cardText = card.getContent().length() > 600
                                                ? card.getContent().substring(0, 600) + "..."
                                                : card.getContent();
                                    }
                                    cardSb.append(cardText).append("\n\n");
                                }
                            }
                            if (!cardSb.isEmpty()) {
                                context.setCharacterCards(cardSb.toString().trim());
                            }
                        }
                    });

            // Next chapter outline (for continuity hints)
            chapterOutlineRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber + 1)
                    .ifPresent(next -> {
                        context.setNextChapterTitle(next.getTitle());
                        context.setNextChapterSummary(next.getSummary());
                    });

            // Previous chapter context (prefer cached summary, fallback to last 300 chars)
            if (chapterNumber > 1) {
                chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber - 1)
                        .ifPresent(prev -> {
                            String prevContext;
                            if (prev.getContentSummary() != null && !prev.getContentSummary().isBlank()) {
                                prevContext = prev.getContentSummary();
                            } else {
                                prevContext = prev.getContent();
                                if (prevContext != null && prevContext.length() > 300) {
                                    prevContext = "..." + prevContext.substring(prevContext.length() - 300);
                                }
                            }
                            context.setPreviousChapterContent(prevContext);
                            if (prev.getCharacterStates() != null && !prev.getCharacterStates().isBlank()) {
                                context.setPreviousCharacterStates(prev.getCharacterStates());
                            }
                        });
            }
        }

        // Load step guidance for the current step
        stepGuidanceRepository.findByProjectIdAndStep(projectId, project.getCurrentStep())
                .ifPresent(sg -> context.setStepGuidance(sg.getGuidance()));

        return context;
    }

    /**
     * Check if a character is active in the given volume.
     * Characters with null startVolume/endVolume are considered permanent (always active).
     */
    private boolean isCharacterActiveInVolume(CharacterEntity character, int volumeNumber) {
        Integer start = character.getStartVolume();
        Integer end = character.getEndVolume();
        // null start means permanent (INITIAL characters or legacy data)
        if (start == null) return true;
        if (start > volumeNumber) return false;
        // null end means active from startVolume onwards permanently
        if (end == null) return true;
        return end >= volumeNumber;
    }
}
