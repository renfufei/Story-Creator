package com.storycreator.web;

import com.storycreator.ai.prompt.MaterialDistillationTemplateLoader;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.core.domain.MaterialCategory;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.persistence.entity.MaterialLibraryEntity;
import com.storycreator.persistence.repository.MaterialLibraryRepository;
import com.storycreator.workflow.engine.AiUsageTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class MaterialLibraryService {

    private static final Logger log = LoggerFactory.getLogger(MaterialLibraryService.class);

    private final MaterialLibraryRepository repository;
    private final AiProviderRouter providerRouter;
    private final AiUsageTracker aiUsageTracker;
    private final MaterialDistillationTemplateLoader templateLoader;

    public MaterialLibraryService(MaterialLibraryRepository repository,
                                  AiProviderRouter providerRouter,
                                  AiUsageTracker aiUsageTracker,
                                  MaterialDistillationTemplateLoader templateLoader) {
        this.repository = repository;
        this.providerRouter = providerRouter;
        this.aiUsageTracker = aiUsageTracker;
        this.templateLoader = templateLoader;
    }

    /**
     * Distill raw content using AI and save to material library.
     */
    public MaterialLibraryEntity distill(Long projectId, String rawContent, MaterialCategory category,
                                          String name, String sourceHint) {
        return distill(projectId, rawContent, category, name, sourceHint, null);
    }

    /**
     * Distill raw content using AI and save to material library.
     * @param configId optional AI model config ID; null means use global default
     */
    public MaterialLibraryEntity distill(Long projectId, String rawContent, MaterialCategory category,
                                          String name, String sourceHint, Long configId) {
        String template = templateLoader.getTemplate(category.name());
        String systemPrompt = templateLoader.getSystemPrompt(category.name());

        if (template == null) {
            template = templateLoader.getTemplate("OTHER");
            systemPrompt = templateLoader.getSystemPrompt("OTHER");
        }

        String prompt = template.replace("{{content}}", rawContent);

        AiProviderRouter.ResolvedModel resolved;
        if (configId != null) {
            resolved = providerRouter.resolveModelByConfigId(configId);
            if (resolved == null) {
                throw new RuntimeException("指定的模型配置不可用(ID=" + configId + ")");
            }
        } else {
            resolved = providerRouter.resolveModel(projectId, WorkflowStep.WORLD_BUILDING);
        }

        AiRequest request = AiRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(prompt)
                .maxTokens(1024)
                .temperature(0.4)
                .build();
        if (resolved.modelId() != null) request.setModel(resolved.modelId());
        if (resolved.baseUrl() != null) request.setBaseUrl(resolved.baseUrl());
        if (resolved.apiKey() != null) request.setApiKey(resolved.apiKey());
        if (resolved.extraParams() != null) request.setExtraParams(resolved.extraParams());

        StringBuilder sb = new StringBuilder();
        long startTime = System.currentTimeMillis();
        try {
            resolved.provider().streamText(request)
                    .doOnNext(sb::append)
                    .blockLast();
            aiUsageTracker.record(projectId, resolved.modelId(), resolved.provider().getProviderName(),
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("Distillation failed for project {}: {}", projectId, e.getMessage());
            throw new RuntimeException("AI蒸馏失败: " + e.getMessage(), e);
        }

        String distilled = sb.toString().trim();
        log.info("[P{}] Distillation complete category={} ({}→{} chars)", projectId, category,
                rawContent.length(), distilled.length());

        MaterialLibraryEntity entity = new MaterialLibraryEntity();
        entity.setName(name);
        entity.setCategory(category);
        entity.setContent(distilled);
        entity.setSourceHint(sourceHint);
        return repository.save(entity);
    }

    /**
     * Build injection string from selected material IDs.
     */
    public String listForInjection(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        List<MaterialLibraryEntity> materials = repository.findByIdIn(ids);
        if (materials.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【参考素材库】\n");
        for (MaterialLibraryEntity m : materials) {
            sb.append("〔").append(m.getCategory().getDisplayName()).append("〕")
              .append(m.getName()).append("\n")
              .append(m.getContent()).append("\n\n");
        }
        sb.append("以上素材仅供参考借鉴，请根据当前作品需要灵活运用。");
        return sb.toString();
    }

    public List<MaterialLibraryEntity> findAll() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public List<MaterialLibraryEntity> findByCategory(MaterialCategory category) {
        return repository.findByCategoryOrderByUpdatedAtDesc(category);
    }

    public MaterialLibraryEntity findById(Long id) {
        return repository.findById(id).orElse(null);
    }

    public MaterialLibraryEntity save(MaterialLibraryEntity entity) {
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
