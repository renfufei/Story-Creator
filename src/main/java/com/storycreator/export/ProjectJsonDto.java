package com.storycreator.export;

import java.util.List;

public record ProjectJsonDto(
    int version,
    ProjectData project,
    WorldSettingData worldSetting,
    List<CharacterData> characters,
    StoryOutlineData storyOutline,
    List<VolumeOutlineData> volumeOutlines,
    List<ChapterOutlineData> chapterOutlines,
    List<ChapterData> chapters,
    List<WorkflowStateData> workflowStates,
    List<StepGuidanceData> stepGuidances,
    List<ProofreadingReportData> proofreadingReports
) {
    public record ProjectData(
        String title,
        String genre,
        String description,
        String currentStep,
        int totalChapters,
        int chapterWordCount,
        int chapterWordCountMin,
        int chapterWordCountMax,
        int characterCount,
        boolean autoMode
    ) {}

    public record WorldSettingData(
        String content,
        String summary
    ) {}

    public record CharacterData(
        String name,
        String role,
        String gender,
        String personality,
        String appearance,
        String background,
        String motivation,
        String relationships,
        String abilities,
        String age,
        String description,
        String content,
        String summary,
        int sortOrder
    ) {}

    public record StoryOutlineData(
        String content,
        int totalChapters
    ) {}

    public record VolumeOutlineData(
        int volumeNumber,
        String title,
        String arcSummary,
        int chapterStart,
        int chapterEnd
    ) {}

    public record ChapterOutlineData(
        int chapterNumber,
        String title,
        String summary,
        String characterNames,
        Integer volumeNumber,
        String status
    ) {}

    public record ChapterData(
        int chapterNumber,
        String title,
        String content,
        String contentDraft,
        int wordCount,
        String status,
        String polishNote,
        String polishStatus,
        String proofreadStatus,
        String plotSummary
    ) {}

    public record WorkflowStateData(
        String step,
        String status,
        String generatedContent,
        String userEditedContent
    ) {}

    public record StepGuidanceData(
        String step,
        String guidance
    ) {}

    public record ProofreadingReportData(
        int chapterNumber,
        String plotSummary,
        String characterIssues,
        String consistencyIssues,
        String continuityIssues,
        String foreshadowing
    ) {}
}
