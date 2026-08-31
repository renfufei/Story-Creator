package com.storycreator.workflow.engine;

/**
 * Encapsulates the results of proofreading a single chapter.
 */
public record ProofreadingResults(
    String plotSummary,
    String foreshadowing
) {}
