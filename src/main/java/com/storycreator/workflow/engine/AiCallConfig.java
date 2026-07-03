package com.storycreator.workflow.engine;

import com.storycreator.ai.router.AiProviderRouter;

/**
 * Bundles AI model resolution and step guidance for passing to generation methods.
 */
public record AiCallConfig(
    AiProviderRouter.ResolvedModel resolved,
    String guidanceSuffix
) {}
