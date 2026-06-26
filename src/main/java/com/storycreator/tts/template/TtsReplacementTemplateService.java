package com.storycreator.tts.template;

import com.storycreator.persistence.entity.TtsModelTemplateBindingEntity;
import com.storycreator.persistence.entity.TtsReplacementRuleEntity;
import com.storycreator.persistence.entity.TtsReplacementTemplateEntity;
import com.storycreator.persistence.repository.TtsModelTemplateBindingRepository;
import com.storycreator.persistence.repository.TtsReplacementRuleRepository;
import com.storycreator.persistence.repository.TtsReplacementTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TtsReplacementTemplateService {

    private static final Logger log = LoggerFactory.getLogger(TtsReplacementTemplateService.class);

    private final TtsReplacementBuiltinLoader builtinLoader;
    private final TtsReplacementTemplateRepository templateRepository;
    private final TtsReplacementRuleRepository ruleRepository;
    private final TtsModelTemplateBindingRepository bindingRepository;

    public TtsReplacementTemplateService(TtsReplacementBuiltinLoader builtinLoader,
                                          TtsReplacementTemplateRepository templateRepository,
                                          TtsReplacementRuleRepository ruleRepository,
                                          TtsModelTemplateBindingRepository bindingRepository) {
        this.builtinLoader = builtinLoader;
        this.templateRepository = templateRepository;
        this.ruleRepository = ruleRepository;
        this.bindingRepository = bindingRepository;
    }

    /**
     * Resolve all replacement rules for a given TTS config.
     * If configId is null or no bindings exist, falls back to all builtin templates (backward-compatible).
     */
    public List<TtsReplacementRule> resolveRulesForConfig(Long configId) {
        if (configId == null) {
            List<TtsReplacementRule> allRules = new ArrayList<>();
            for (BuiltinTtsTemplate template : builtinLoader.getAll()) {
                allRules.addAll(template.rules());
            }
            return allRules;
        }
        List<TtsModelTemplateBindingEntity> bindings = bindingRepository.findByModelConfigIdOrderBySortOrder(configId);

        if (bindings.isEmpty()) {
            // Fallback: load all builtin templates
            List<TtsReplacementRule> allRules = new ArrayList<>();
            for (BuiltinTtsTemplate template : builtinLoader.getAll()) {
                allRules.addAll(template.rules());
            }
            return allRules;
        }

        List<TtsReplacementRule> rules = new ArrayList<>();
        for (TtsModelTemplateBindingEntity binding : bindings) {
            String ref = binding.getTemplateRef();
            if (ref.startsWith("builtin:")) {
                String builtinId = ref.substring("builtin:".length());
                builtinLoader.findById(builtinId).ifPresent(t -> rules.addAll(t.rules()));
            } else if (ref.startsWith("user:")) {
                Long templateId = Long.parseLong(ref.substring("user:".length()));
                templateRepository.findById(templateId).ifPresent(t -> {
                    if (t.isEnabled()) {
                        for (TtsReplacementRuleEntity ruleEntity : t.getRules()) {
                            rules.add(new TtsReplacementRule(
                                    ruleEntity.getPattern(),
                                    ruleEntity.getReplacement(),
                                    ruleEntity.isRegex(),
                                    ruleEntity.getDescription()
                            ));
                        }
                    }
                });
            }
        }
        return rules;
    }

    // --- User template CRUD ---

    public List<TtsReplacementTemplateEntity> listUserTemplates() {
        return templateRepository.findAllByOrderBySortOrder();
    }

    @Transactional
    public TtsReplacementTemplateEntity createTemplate(String name, String description) {
        TtsReplacementTemplateEntity entity = new TtsReplacementTemplateEntity();
        entity.setName(name);
        entity.setDescription(description);
        entity.setEnabled(true);
        entity.setSortOrder(0);
        return templateRepository.save(entity);
    }

    @Transactional
    public TtsReplacementTemplateEntity copyFromBuiltin(String builtinId) {
        BuiltinTtsTemplate builtin = builtinLoader.findById(builtinId)
                .orElseThrow(() -> new IllegalArgumentException("Builtin template not found: " + builtinId));

        TtsReplacementTemplateEntity template = new TtsReplacementTemplateEntity();
        template.setName(builtin.name() + "（副本）");
        template.setDescription(builtin.description());
        template.setEnabled(true);
        template.setSortOrder(0);
        template = templateRepository.save(template);

        int order = 0;
        for (TtsReplacementRule rule : builtin.rules()) {
            TtsReplacementRuleEntity ruleEntity = new TtsReplacementRuleEntity();
            ruleEntity.setTemplate(template);
            ruleEntity.setPattern(rule.pattern());
            ruleEntity.setReplacement(rule.replacement());
            ruleEntity.setRegex(rule.isRegex());
            ruleEntity.setDescription(rule.description());
            ruleEntity.setSortOrder(order++);
            ruleRepository.save(ruleEntity);
        }

        return template;
    }

    @Transactional
    public void deleteTemplate(Long templateId) {
        bindingRepository.deleteByTemplateRef("user:" + templateId);
        templateRepository.deleteById(templateId);
    }

    @Transactional
    public void toggleTemplate(Long templateId) {
        TtsReplacementTemplateEntity template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
        template.setEnabled(!template.isEnabled());
        templateRepository.save(template);
    }

    @Transactional
    public void updateTemplate(Long templateId, String name, String description) {
        TtsReplacementTemplateEntity template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));
        template.setName(name);
        template.setDescription(description);
        templateRepository.save(template);
    }

    // --- Rule CRUD ---

    @Transactional
    public TtsReplacementRuleEntity addRule(Long templateId, String pattern, String replacement,
                                             boolean isRegex, String description, int sortOrder) {
        TtsReplacementTemplateEntity template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        TtsReplacementRuleEntity rule = new TtsReplacementRuleEntity();
        rule.setTemplate(template);
        rule.setPattern(pattern);
        rule.setReplacement(replacement != null ? replacement : "");
        rule.setRegex(isRegex);
        rule.setDescription(description);
        rule.setSortOrder(sortOrder);
        return ruleRepository.save(rule);
    }

    @Transactional
    public void updateRule(Long ruleId, String pattern, String replacement,
                           boolean isRegex, String description, int sortOrder) {
        TtsReplacementRuleEntity rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
        rule.setPattern(pattern);
        rule.setReplacement(replacement != null ? replacement : "");
        rule.setRegex(isRegex);
        rule.setDescription(description);
        rule.setSortOrder(sortOrder);
        ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long ruleId) {
        ruleRepository.deleteById(ruleId);
    }

    public List<TtsReplacementRuleEntity> getRulesForTemplate(Long templateId) {
        return ruleRepository.findByTemplateIdOrderBySortOrder(templateId);
    }

    // --- Binding management ---

    public List<TtsModelTemplateBindingEntity> getBindingsForConfig(Long configId) {
        return bindingRepository.findByModelConfigIdOrderBySortOrder(configId);
    }

    @Transactional
    public void bindTemplateToConfig(Long configId, String templateRef, int sortOrder) {
        TtsModelTemplateBindingEntity binding = bindingRepository
                .findByModelConfigIdAndTemplateRef(configId, templateRef)
                .orElseGet(() -> {
                    TtsModelTemplateBindingEntity b = new TtsModelTemplateBindingEntity();
                    b.setModelConfigId(configId);
                    b.setTemplateRef(templateRef);
                    return b;
                });
        binding.setSortOrder(sortOrder);
        bindingRepository.save(binding);
    }

    @Transactional
    public void unbindTemplateFromConfig(Long configId, String templateRef) {
        bindingRepository.deleteByModelConfigIdAndTemplateRef(configId, templateRef);
    }

    /**
     * Get all available template references (builtin + user) for binding selection.
     */
    public List<TemplateOption> getAllTemplateOptions() {
        List<TemplateOption> options = new ArrayList<>();
        for (BuiltinTtsTemplate builtin : builtinLoader.getAll()) {
            options.add(new TemplateOption("builtin:" + builtin.id(), builtin.name(), "内置", true));
        }
        for (TtsReplacementTemplateEntity userTemplate : templateRepository.findAllByOrderBySortOrder()) {
            options.add(new TemplateOption("user:" + userTemplate.getId(), userTemplate.getName(), "自定义",
                    userTemplate.isEnabled()));
        }
        return options;
    }

    public record TemplateOption(String ref, String name, String type, boolean enabled) {}
}
