package com.storycreator.web;

/**
 * Encapsulates the context parameters for prompt exploration.
 */
public record PromptExploreContext(
    Long projectId,
    Integer chapterNumber,
    Long characterId,
    Integer cardNumber,
    Integer totalCards,
    Integer volumeNumber
) {}
