package com.storycreator.workflow.autorun.strategy;

import com.storycreator.workflow.autorun.AutoRunContext;

/**
 * Strategy interface for auto-run orchestration.
 * Implementations define how a project's workflow steps are executed.
 */
public interface AutoRunStrategy {

    /**
     * Unique name for this strategy (e.g. "DEFAULT", "PARALLEL_CHAPTERS").
     */
    String getName();

    /**
     * Execute the auto-run workflow.
     * <ul>
     *   <li>Normal return = completed (caller checks shouldStop to distinguish stop vs true completion)</li>
     *   <li>Exception = failure</li>
     *   <li>Must periodically check ctx.shouldStop() for cooperative cancellation</li>
     * </ul>
     */
    void execute(AutoRunContext ctx) throws Exception;
}
