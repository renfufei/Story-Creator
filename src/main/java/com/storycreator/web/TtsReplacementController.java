package com.storycreator.web;

import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.TtsModelTemplateBindingEntity;
import com.storycreator.persistence.entity.TtsReplacementRuleEntity;
import com.storycreator.persistence.entity.TtsReplacementTemplateEntity;
import com.storycreator.persistence.repository.TtsReplacementTemplateRepository;
import com.storycreator.tts.template.BuiltinTtsTemplate;
import com.storycreator.tts.template.TtsReplacementBuiltinLoader;
import com.storycreator.tts.template.TtsReplacementTemplateService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/settings/tts-templates")
public class TtsReplacementController {

    private final TtsReplacementTemplateService templateService;
    private final TtsReplacementBuiltinLoader builtinLoader;
    private final TtsReplacementTemplateRepository templateRepository;
    private final TtsProviderRegistry ttsProviderRegistry;

    public TtsReplacementController(TtsReplacementTemplateService templateService,
                                     TtsReplacementBuiltinLoader builtinLoader,
                                     TtsReplacementTemplateRepository templateRepository,
                                     TtsProviderRegistry ttsProviderRegistry) {
        this.templateService = templateService;
        this.builtinLoader = builtinLoader;
        this.templateRepository = templateRepository;
        this.ttsProviderRegistry = ttsProviderRegistry;
    }

    @GetMapping
    public String listTemplates(Model model) {
        List<BuiltinTtsTemplate> builtinTemplates = builtinLoader.getAll();
        List<TtsReplacementTemplateEntity> userTemplates = templateService.listUserTemplates();
        model.addAttribute("builtinTemplates", builtinTemplates);
        model.addAttribute("userTemplates", userTemplates);
        return "tts-templates";
    }

    @PostMapping
    public String createTemplate(@RequestParam String name,
                                  @RequestParam(defaultValue = "") String description) {
        templateService.createTemplate(name, description);
        return "redirect:/settings/tts-templates";
    }

    @GetMapping("/{id}/edit")
    public String editTemplate(@PathVariable Long id, Model model) {
        TtsReplacementTemplateEntity template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
        List<TtsReplacementRuleEntity> rules = templateService.getRulesForTemplate(id);
        model.addAttribute("template", template);
        model.addAttribute("rules", rules);
        return "tts-template-edit";
    }

    @PostMapping("/{id}/update")
    public String updateTemplate(@PathVariable Long id,
                                  @RequestParam String name,
                                  @RequestParam(defaultValue = "") String description) {
        templateService.updateTemplate(id, name, description);
        return "redirect:/settings/tts-templates/" + id + "/edit";
    }

    @PostMapping("/{id}/toggle")
    public String toggleTemplate(@PathVariable Long id) {
        templateService.toggleTemplate(id);
        return "redirect:/settings/tts-templates";
    }

    @PostMapping("/{id}/delete")
    public String deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return "redirect:/settings/tts-templates";
    }

    @GetMapping("/builtin/{builtinId}/view")
    public String viewBuiltin(@PathVariable String builtinId, Model model) {
        BuiltinTtsTemplate template = builtinLoader.getAll().stream()
                .filter(t -> t.id().equals(builtinId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Builtin template not found: " + builtinId));
        model.addAttribute("template", template);
        return "tts-template-view";
    }

    @PostMapping("/builtin/{builtinId}/copy")
    public String copyBuiltin(@PathVariable String builtinId) {
        templateService.copyFromBuiltin(builtinId);
        return "redirect:/settings/tts-templates";
    }

    // --- Rule CRUD ---

    @PostMapping("/{templateId}/rules")
    public String addRule(@PathVariable Long templateId,
                           @RequestParam String pattern,
                           @RequestParam(defaultValue = "") String replacement,
                           @RequestParam(defaultValue = "false") boolean isRegex,
                           @RequestParam(defaultValue = "") String description,
                           @RequestParam(defaultValue = "0") int sortOrder) {
        templateService.addRule(templateId, pattern, replacement, isRegex, description, sortOrder);
        return "redirect:/settings/tts-templates/" + templateId + "/edit";
    }

    @PostMapping("/{templateId}/rules/{ruleId}/update")
    public String updateRule(@PathVariable Long templateId,
                              @PathVariable Long ruleId,
                              @RequestParam String pattern,
                              @RequestParam(defaultValue = "") String replacement,
                              @RequestParam(defaultValue = "false") boolean isRegex,
                              @RequestParam(defaultValue = "") String description,
                              @RequestParam(defaultValue = "0") int sortOrder) {
        templateService.updateRule(ruleId, pattern, replacement, isRegex, description, sortOrder);
        return "redirect:/settings/tts-templates/" + templateId + "/edit";
    }

    @PostMapping("/{templateId}/rules/{ruleId}/delete")
    public String deleteRule(@PathVariable Long templateId, @PathVariable Long ruleId) {
        templateService.deleteRule(ruleId);
        return "redirect:/settings/tts-templates/" + templateId + "/edit";
    }

    // --- Binding management ---

    @GetMapping("/bindings/{configId}")
    public String manageBindings(@PathVariable Long configId, Model model) {
        List<TtsModelTemplateBindingEntity> bindings = templateService.getBindingsForConfig(configId);
        List<TtsReplacementTemplateService.TemplateOption> allOptions = templateService.getAllTemplateOptions();
        Map<String, String> templateNameMap = allOptions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        TtsReplacementTemplateService.TemplateOption::ref,
                        TtsReplacementTemplateService.TemplateOption::name,
                        (a, b) -> a));
        List<AiModelConfigEntity> ttsConfigs = ttsProviderRegistry.getActiveTtsConfigs();
        AiModelConfigEntity currentConfig = ttsConfigs.stream()
                .filter(c -> c.getId().equals(configId))
                .findFirst().orElse(null);
        model.addAttribute("bindings", bindings);
        model.addAttribute("allOptions", allOptions);
        model.addAttribute("templateNameMap", templateNameMap);
        model.addAttribute("configId", configId);
        model.addAttribute("currentConfig", currentConfig);
        return "tts-template-bindings";
    }

    @PostMapping("/bindings/{configId}/add")
    public String addBinding(@PathVariable Long configId,
                              @RequestParam String templateRef,
                              @RequestParam(defaultValue = "0") int sortOrder) {
        templateService.bindTemplateToConfig(configId, templateRef, sortOrder);
        return "redirect:/settings/tts-templates/bindings/" + configId;
    }

    @PostMapping("/bindings/{configId}/delete")
    public String removeBinding(@PathVariable Long configId,
                                 @RequestParam String templateRef) {
        templateService.unbindTemplateFromConfig(configId, templateRef);
        return "redirect:/settings/tts-templates/bindings/" + configId;
    }
}
