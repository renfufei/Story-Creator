package com.storycreator.workflow.engine;

import com.storycreator.ai.prompt.WorldFacetTemplateLoader;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.WorldFacetKey;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.WorldSettingFacetEntity;
import com.storycreator.persistence.repository.WorldSettingFacetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorldFacetElaborationService {

    private static final Logger log = LoggerFactory.getLogger(WorldFacetElaborationService.class);

    private final WorldSettingFacetRepository facetRepository;
    private final AiProviderRouter providerRouter;
    private final AiUsageTracker aiUsageTracker;
    private final WorldFacetTemplateLoader templateLoader;

    public WorldFacetElaborationService(WorldSettingFacetRepository facetRepository,
                                        AiProviderRouter providerRouter,
                                        AiUsageTracker aiUsageTracker,
                                        WorldFacetTemplateLoader templateLoader) {
        this.facetRepository = facetRepository;
        this.providerRouter = providerRouter;
        this.aiUsageTracker = aiUsageTracker;
        this.templateLoader = templateLoader;
    }

    /**
     * Asynchronously elaborate all facets for a project.
     * Runs on a virtual thread to avoid blocking the caller.
     */
    public void elaborateAllFacetsAsync(Long projectId, String worldSettingContent) {
        Thread.startVirtualThread(() -> {
            try {
                elaborateAllFacets(projectId, worldSettingContent);
            } catch (Exception e) {
                log.error("[P{}] Failed to elaborate world facets asynchronously", projectId, e);
            }
        });
    }

    /**
     * Elaborate all 4 facets for the given world setting content.
     * Each facet is processed independently — one failure doesn't affect others.
     */
    public void elaborateAllFacets(Long projectId, String worldSettingContent) {
        if (worldSettingContent == null || worldSettingContent.length() < 200) {
            log.info("[P{}] World setting too short for facet elaboration ({}chars), skipping",
                    projectId, worldSettingContent == null ? 0 : worldSettingContent.length());
            return;
        }

        String contentHash = computeHash(worldSettingContent);
        log.info("[P{}] Starting world facet elaboration (content hash: {})", projectId, contentHash.substring(0, 8));

        for (WorldFacetKey facetKey : WorldFacetKey.values()) {
            try {
                elaborateSingleFacet(projectId, facetKey, worldSettingContent, contentHash);
            } catch (Exception e) {
                log.warn("[P{}] Failed to elaborate facet {}: {}", projectId, facetKey, e.getMessage());
            }
        }

        log.info("[P{}] World facet elaboration completed", projectId);
    }

    @Transactional
    public void elaborateSingleFacet(Long projectId, WorldFacetKey facetKey,
                                      String worldSettingContent, String contentHash) {
        // Check if already up-to-date
        var existing = facetRepository.findByProjectIdAndFacetKey(projectId, facetKey).orElse(null);
        if (existing != null && contentHash.equals(existing.getContentHash())) {
            log.debug("[P{}] Facet {} already up-to-date, skipping", projectId, facetKey);
            return;
        }

        // Load template
        String template = templateLoader.getTemplate(facetKey.name());
        String systemPrompt = templateLoader.getSystemPrompt(facetKey.name());
        if (template == null) {
            log.warn("[P{}] No template found for facet {}", projectId, facetKey);
            return;
        }

        // Resolve model
        AiProviderRouter.ResolvedModel resolved = providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);

        // Build prompt
        String userPrompt = template.replace("{{content}}", worldSettingContent);
        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(1024)
                .temperature(0.4)
                .build();
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());

        // Call AI
        StringBuilder sb = new StringBuilder();
        long startTime = System.currentTimeMillis();
        resolved.provider().streamText(request)
                .doOnNext(sb::append)
                .blockLast();
        long elapsed = System.currentTimeMillis() - startTime;
        aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(), elapsed);

        String facetContent = sb.toString().trim();
        log.info("[P{}] Facet {} generated ({}chars, {}s)", projectId, facetKey, facetContent.length(), elapsed / 1000);

        // Save
        WorldSettingFacetEntity entity = existing != null ? existing : new WorldSettingFacetEntity();
        entity.setProjectId(projectId);
        entity.setFacetKey(facetKey);
        entity.setContent(facetContent);
        entity.setContentHash(contentHash);
        facetRepository.save(entity);
    }

    /**
     * Get a specific facet's content. Returns empty string if not available.
     */
    public String getFacet(Long projectId, WorldFacetKey key) {
        return facetRepository.findByProjectIdAndFacetKey(projectId, key)
                .map(WorldSettingFacetEntity::getContent)
                .orElse("");
    }

    /**
     * Load all facets for a project as a map.
     */
    public Map<WorldFacetKey, String> loadAllFacets(Long projectId) {
        List<WorldSettingFacetEntity> facets = facetRepository.findByProjectId(projectId);
        return facets.stream()
                .filter(f -> f.getContent() != null && !f.getContent().isBlank())
                .collect(Collectors.toMap(WorldSettingFacetEntity::getFacetKey, WorldSettingFacetEntity::getContent));
    }

    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(content.hashCode());
        }
    }
}
