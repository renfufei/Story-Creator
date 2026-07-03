package com.storycreator.workflow.engine;

/**
 * Encapsulates the results of proofreading a single chapter.
 */
public record ProofreadingResults(
    String plotSummary,
    String characterIssues,
    String consistencyIssues,
    String continuityIssues,
    String foreshadowing
) {}
