package com.storycreator.web;

import org.springframework.beans.factory.annotation.Autowired;

import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.TtsModelTemplateBindingEntity;
import com.storycreator.persistence.entity.TtsReplacementRuleEntity;
import com.storycreator.persistence.entity.TtsReplacementTemplateEntity;
import com.storycreator.persistence.repository.TtsReplacementTemplateRepository;
import com.storycreator.tts.template.BuiltinTtsTemplate;
import com.storycreator.tts.template.TtsReplacementBuiltinLoader;
import com.storycreator.tts.template.TtsReplacementRule;
import com.storycreator.tts.template.TtsReplacementTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS 文字替换模板（静态页 + 引导 JSON）。
 *
 * <p>4 个页面：列表 {@code /settings/tts-templates}、自定义模板编辑 {@code /{id}/edit}、
 * 内置模板查看 {@code /builtin/{id}/view}、模型绑定 {@code /bindings/{configId}}。
 * 页面 URL 不变，各自返回 {@code forward:/pages/*.html}；引导数据由 {@code *-data} 端点提供，
 * 静态页用同步 XHR 注入全局变量。表单类操作（新建/更新/删除/规则增删/绑定）仍为原生 form POST。
 */
@Controller
@RequestMapping("/settings/tts-templates")
public class TtsReplacementController {

    private TtsReplacementTemplateService templateService;
    private TtsReplacementBuiltinLoader builtinLoader;
    private TtsReplacementTemplateRepository templateRepository;
    private TtsProviderRegistry ttsProviderRegistry;

    @Autowired
    public void setTemplateService(TtsReplacementTemplateService templateService) {
        this.templateService = templateService;
    }

    @Autowired
    public void setBuiltinLoader(TtsReplacementBuiltinLoader builtinLoader) {
        this.builtinLoader = builtinLoader;
    }

    @Autowired
    public void setTemplateRepository(TtsReplacementTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Autowired
    public void setTtsProviderRegistry(TtsProviderRegistry ttsProviderRegistry) {
        this.ttsProviderRegistry = ttsProviderRegistry;
    }


    @GetMapping
    public String listTemplates() {
        return "forward:/pages/tts-templates.html";
    }

    /** 列表页引导数据：内置模板 + 自定义模板。 */
    @GetMapping("/data")
    @ResponseBody
    public Map<String, Object> listData() {
        List<Map<String, Object>> builtin = builtinLoader.getAll().stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.id());
                    m.put("name", t.name());
                    m.put("description", t.description() == null ? "" : t.description());
                    m.put("ruleCount", t.rules() == null ? 0 : t.rules().size());
                    return m;
                })
                .toList();
        List<Map<String, Object>> user = templateService.listUserTemplates().stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("description", t.getDescription() == null ? "" : t.getDescription());
                    m.put("ruleCount", t.getRules() == null ? 0 : t.getRules().size());
                    m.put("enabled", t.isEnabled());
                    return m;
                })
                .toList();
        Map<String, Object> result = new HashMap<>();
        result.put("builtinTemplates", builtin);
        result.put("userTemplates", user);
        return result;
    }

    @PostMapping
    public String createTemplate(@RequestParam String name,
                                  @RequestParam(defaultValue = "") String description) {
        templateService.createTemplate(name, description);
        return "redirect:/settings/tts-templates";
    }

    @GetMapping("/{id}/edit")
    public String editTemplate(@PathVariable Long id) {
        return "forward:/pages/tts-template-edit.html";
    }

    /** 编辑页引导数据：模板信息 + 规则列表。缺失 id → 404。 */
    @GetMapping("/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> editData(@PathVariable Long id) {
        TtsReplacementTemplateEntity template = templateRepository.findById(id).orElse(null);
        if (template == null) return ResponseEntity.notFound().build();
        Map<String, Object> t = new HashMap<>();
        t.put("id", template.getId());
        t.put("name", template.getName());
        t.put("description", template.getDescription() == null ? "" : template.getDescription());
        List<Map<String, Object>> rules = templateService.getRulesForTemplate(id).stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("sortOrder", r.getSortOrder());
                    m.put("pattern", r.getPattern());
                    m.put("replacement", r.getReplacement() == null ? "" : r.getReplacement());
                    m.put("regex", r.isRegex());
                    m.put("description", r.getDescription() == null ? "" : r.getDescription());
                    return m;
                })
                .toList();
        Map<String, Object> result = new HashMap<>();
        result.put("template", t);
        result.put("rules", rules);
        return ResponseEntity.ok(result);
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
    public String viewBuiltin(@PathVariable String builtinId) {
        return "forward:/pages/tts-template-view.html";
    }

    /** 内置模板查看页引导数据。未知 builtinId → 404。 */
    @GetMapping("/builtin/{builtinId}/view-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> viewData(@PathVariable String builtinId) {
        BuiltinTtsTemplate template = builtinLoader.getAll().stream()
                .filter(t -> t.id().equals(builtinId))
                .findFirst().orElse(null);
        if (template == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new HashMap<>();
        result.put("id", template.id());
        result.put("name", template.name());
        result.put("description", template.description() == null ? "" : template.description());
        List<Map<String, Object>> rules = new ArrayList<>();
        if (template.rules() != null) {
            for (TtsReplacementRule r : template.rules()) {
                Map<String, Object> m = new HashMap<>();
                m.put("pattern", r.pattern());
                m.put("replacement", r.replacement() == null ? "" : r.replacement());
                m.put("regex", r.isRegex());
                m.put("description", r.description() == null ? "" : r.description());
                rules.add(m);
            }
        }
        result.put("rules", rules);
        return ResponseEntity.ok(result);
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
    public String manageBindings(@PathVariable Long configId) {
        return "forward:/pages/tts-template-bindings.html";
    }

    /** 绑定管理页引导数据：当前绑定 + 可选模板 + 当前 TTS 模型。 */
    @GetMapping("/bindings/{configId}/data")
    @ResponseBody
    public Map<String, Object> bindingsData(@PathVariable Long configId) {
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

        List<Map<String, Object>> bindingList = bindings.stream()
                .map(b -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("templateRef", b.getTemplateRef());
                    m.put("sortOrder", b.getSortOrder());
                    m.put("name", templateNameMap.getOrDefault(b.getTemplateRef(), b.getTemplateRef()));
                    m.put("type", b.getTemplateRef().startsWith("builtin:") ? "builtin"
                            : (b.getTemplateRef().startsWith("user:") ? "user" : "other"));
                    return m;
                })
                .toList();
        List<Map<String, Object>> optionList = allOptions.stream()
                .map(o -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("ref", o.ref());
                    m.put("name", o.name());
                    m.put("type", o.type());
                    m.put("enabled", o.enabled());
                    return m;
                })
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("configId", configId);
        result.put("currentConfig", currentConfig == null ? null : Map.of(
                "id", currentConfig.getId(),
                "displayName", currentConfig.getDisplayName() == null ? "" : currentConfig.getDisplayName(),
                "modelId", currentConfig.getModelId() == null ? "" : currentConfig.getModelId()));
        result.put("bindings", bindingList);
        result.put("allOptions", optionList);
        return result;
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
