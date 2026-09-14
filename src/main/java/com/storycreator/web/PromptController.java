package com.storycreator.web;

import com.storycreator.ai.prompt.BuiltinTemplate;
import com.storycreator.ai.prompt.BuiltinTemplateLoader;
import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.prompt.TemplateWorkflowTag;
import com.storycreator.ai.prompt.TemplateWorkflowUsage;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Controller
@RequestMapping("/prompts")
public class PromptController {

    private final PromptTemplateRepository repository;
    private final PromptTemplateRegistry promptRegistry;
    private final BuiltinTemplateLoader builtinLoader;

    public PromptController(PromptTemplateRepository repository, PromptTemplateRegistry promptRegistry,
                           BuiltinTemplateLoader builtinLoader) {
        this.repository = repository;
        this.promptRegistry = promptRegistry;
        this.builtinLoader = builtinLoader;
    }

    /**
     * DTO for template list display, unifying builtin and custom templates.
     */
    public record TemplateListItem(
        Long id,                // null for builtin
        String key,             // builtin key or null for custom
        WorkflowStep step,
        PromptSubStep subStep,
        Genre genre,
        String name,
        boolean builtin,
        boolean isDefault,      // true for builtin (always active), or custom with isDefault=true
        String updatedAt,
        int sortOrder,
        Set<TemplateWorkflowTag> workflowTags
    ) {
        public String workflowTagNamesCsv() {
            if (workflowTags == null || workflowTags.isEmpty()) return "";
            return workflowTags.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
        }
    }

    /**
     * 页面路由：转发到静态页（前端已从 Thymeleaf 迁到「静态 HTML + JS + Ajax」）。
     * 引导数据由 {@link #listData()} 以 JSON 提供。
     */
    @GetMapping
    public String list() {
        return "forward:/pages/prompts.html";
    }

    /** 提示词模板列表页的引导数据（templates + 步骤/题材/子步骤枚举选项）。 */
    @GetMapping("/data")
    @ResponseBody
    public Map<String, Object> listData() {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (TemplateListItem it : buildItems()) {
            templates.add(templateToMap(it));
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        for (WorkflowStep s : WorkflowStep.values()) {
            steps.add(Map.of("value", s.name(), "displayName", s.getDisplayName()));
        }

        List<Map<String, Object>> genres = new ArrayList<>();
        for (Genre g : Genre.values()) {
            genres.add(Map.of("value", g.name(), "displayName", g.getDisplayName()));
        }

        List<Map<String, Object>> subSteps = new ArrayList<>();
        for (PromptSubStep ss : PromptSubStep.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", ss.name());
            m.put("displayName", ss.getDisplayName());
            WorkflowStep parent = ss.getParentStep();
            m.put("parentStep", parent != null ? parent.name() : null);
            subSteps.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templates", templates);
        data.put("steps", steps);
        data.put("genres", genres);
        data.put("subSteps", subSteps);
        return data;
    }

    /** 汇总内置与自定义模板为统一列表（内置在前、同 sortOrder 时内置优先）。 */
    private List<TemplateListItem> buildItems() {
        List<TemplateListItem> items = new ArrayList<>();

        // Add builtin templates
        for (BuiltinTemplate bt : builtinLoader.getAll()) {
            // Check if there's a custom override that is set as default for this step+subStep+genre
            boolean hasCustomOverride = hasCustomDefault(bt.step(), bt.subStep(), bt.genre());
            items.add(new TemplateListItem(
                null, bt.key(), bt.step(), bt.subStep(), bt.genre(),
                bt.name(), true, !hasCustomOverride, null,
                bt.sortOrder(), bt.workflowTags()
            ));
        }

        // Add custom templates from DB
        for (PromptTemplateEntity t : repository.findAll()) {
            int order = t.getSubStep() != null ? t.getSubStep().getSortOrder() : (t.getStep().getOrder() * 10);
            Set<TemplateWorkflowTag> tags = t.getSubStep() != null
                    ? TemplateWorkflowUsage.getTagsFor(t.getSubStep()) : Set.of();
            items.add(new TemplateListItem(
                t.getId(), null, t.getStep(), t.getSubStep(), t.getGenre(),
                t.getName(), false, t.isDefault(),
                t.getUpdatedAt() != null ? t.getUpdatedAt().toString().substring(0, 16).replace('T', ' ') : null,
                order, tags
            ));
        }

        // Sort: by sortOrder, then builtin before custom (so custom overrides appear right after their builtin)
        items.sort(Comparator.comparingInt(TemplateListItem::sortOrder)
                .thenComparing(t -> t.builtin() ? 0 : 1));
        return items;
    }

    /** 单个模板 -> 前端渲染所需字段（含枚举 displayName，避免前端重复维护映射）。 */
    private Map<String, Object> templateToMap(TemplateListItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.id());
        m.put("key", it.key());
        m.put("step", it.step() != null ? it.step().name() : null);
        m.put("stepDisplayName", it.step() != null ? it.step().getDisplayName() : "");
        m.put("subStep", it.subStep() != null ? it.subStep().name() : null);
        m.put("subStepDisplayName", it.subStep() != null ? it.subStep().getDisplayName() : null);
        m.put("genre", it.genre() != null ? it.genre().name() : null);
        m.put("genreDisplayName", it.genre() != null ? it.genre().getDisplayName() : "通用");
        m.put("name", it.name());
        m.put("builtin", it.builtin());
        m.put("isDefault", it.isDefault());
        m.put("updatedAt", it.updatedAt());
        m.put("workflowTagNamesCsv", it.workflowTagNamesCsv());

        List<Map<String, Object>> tags = new ArrayList<>();
        if (it.workflowTags() != null) {
            for (TemplateWorkflowTag tag : it.workflowTags()) {
                tags.add(Map.of("name", tag.name(), "displayName", tag.getDisplayName()));
            }
        }
        m.put("workflowTags", tags);
        return m;
    }

    private boolean hasCustomDefault(WorkflowStep step, PromptSubStep subStep, Genre genre) {
        // Check if a DB (custom) template is set as default for the same step+subStep+genre.
        // For PRIMARY sub-steps, DB overrides use subStep=null (main-step override convention).
        if (subStep == null || subStep.isPrimary()) {
            if (genre != null) {
                return repository.findByStepAndGenreAndIsDefaultTrue(step, genre).isPresent();
            }
            return repository.findByStepAndGenreIsNullAndIsDefaultTrue(step).isPresent();
        } else {
            if (genre != null) {
                return repository.findByStepAndSubStepAndGenreAndIsDefaultTrue(step, subStep, genre).isPresent();
            }
            return repository.findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(step, subStep).isPresent();
        }
    }

    /**
     * 内置模板查看页：转发到静态页（只读展示）。
     * 引导数据由 {@link #builtinEditData(String)} 以 JSON 提供。
     */
    @GetMapping("/builtin/{key}")
    public String viewBuiltin(@PathVariable String key) {
        return "forward:/pages/prompt-edit.html";
    }

    /**
     * 自定义模板编辑页：转发到静态页。
     * 引导数据由 {@link #customEditData(Long)} 以 JSON 提供。
     */
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id) {
        return "forward:/pages/prompt-edit.html";
    }

    /** 内置模板查看页的引导数据（只读）。 */
    @GetMapping("/builtin/{key}/edit-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> builtinEditData(@PathVariable String key) {
        BuiltinTemplate bt = builtinLoader.getAll().stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .orElse(null);
        if (bt == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isBuiltin", true);
        m.put("id", null);
        m.put("key", bt.key());
        m.put("name", bt.name());
        m.put("stepDisplayName", bt.step() != null ? bt.step().getDisplayName() : "");
        m.put("subStepDisplayName", bt.subStep() != null ? bt.subStep().getDisplayName() : null);
        m.put("genreDisplayName", bt.genre() != null ? bt.genre().getDisplayName() : "通用");
        m.put("systemPrompt", bt.systemPrompt() != null ? bt.systemPrompt() : "");
        m.put("template", bt.template() != null ? bt.template() : "");
        m.put("variableHints", bt.subStep() != null
                ? PromptTemplateRegistry.SUB_STEP_VARIABLES.get(bt.subStep()) : null);
        return ResponseEntity.ok(m);
    }

    /** 自定义模板编辑页的引导数据。 */
    @GetMapping("/{id}/edit-data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> customEditData(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isBuiltin", false);
        m.put("id", entity.getId());
        m.put("key", null);
        m.put("name", entity.getName());
        m.put("stepDisplayName", entity.getStep() != null ? entity.getStep().getDisplayName() : "");
        m.put("subStepDisplayName", entity.getSubStep() != null ? entity.getSubStep().getDisplayName() : null);
        m.put("genreDisplayName", entity.getGenre() != null ? entity.getGenre().getDisplayName() : "通用");
        m.put("systemPrompt", entity.getSystemPrompt() != null ? entity.getSystemPrompt() : "");
        m.put("template", entity.getTemplate() != null ? entity.getTemplate() : "");
        m.put("variableHints", entity.getSubStep() != null
                ? PromptTemplateRegistry.SUB_STEP_VARIABLES.get(entity.getSubStep()) : null);
        return ResponseEntity.ok(m);
    }

    @GetMapping("/builtin/{key}/json")
    @ResponseBody
    public ResponseEntity<Map<String, String>> viewBuiltinJson(@PathVariable String key) {
        BuiltinTemplate bt = builtinLoader.getAll().stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Builtin template not found: " + key));
        return ResponseEntity.ok(Map.of(
                "template", bt.template() != null ? bt.template() : "",
                "systemPrompt", bt.systemPrompt() != null ? bt.systemPrompt() : ""
        ));
    }

    @GetMapping("/{id}/json")
    @ResponseBody
    public ResponseEntity<Map<String, String>> viewCustomJson(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        return ResponseEntity.ok(Map.of(
                "template", entity.getTemplate() != null ? entity.getTemplate() : "",
                "systemPrompt", entity.getSystemPrompt() != null ? entity.getSystemPrompt() : ""
        ));
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                        @RequestParam String name,
                        @RequestParam(required = false) String systemPrompt,
                        @RequestParam String template) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        entity.setName(name);
        entity.setSystemPrompt(systemPrompt);
        entity.setTemplate(template);
        repository.save(entity);
        return "redirect:/prompts";
    }

    @PostMapping("/{id}/reset")
    public String reset(@PathVariable Long id) {
        // "Reset" now means: delete this custom override so the builtin fallback takes effect
        repository.deleteById(id);
        return "redirect:/prompts";
    }

    @PostMapping("/{id}/unset-default")
    public String unsetDefault(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        entity.setDefault(false);
        repository.save(entity);
        return "redirect:/prompts";
    }

    @PostMapping("/{id}/set-default")
    public String setDefault(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        // Unset default for all templates with same step, sub-step and genre
        repository.findAll().stream()
                .filter(t -> t.getStep() == entity.getStep()
                        && java.util.Objects.equals(t.getSubStep(), entity.getSubStep())
                        && java.util.Objects.equals(t.getGenre(), entity.getGenre())
                        && t.isDefault())
                .forEach(t -> {
                    t.setDefault(false);
                    repository.save(t);
                });
        // Set this one as default
        entity.setDefault(true);
        repository.save(entity);
        return "redirect:/prompts";
    }

    @PostMapping("/{id}/delete")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        repository.delete(entity);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/export")
    @ResponseBody
    public ResponseEntity<byte[]> exportTemplates(@RequestParam List<Long> ids) throws Exception {
        List<PromptTemplateEntity> templates = repository.findAllById(ids);
        List<Map<String, Object>> exportData = templates.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", t.getStep().name());
            m.put("subStep", t.getSubStep() != null ? t.getSubStep().name() : null);
            m.put("genre", t.getGenre() != null ? t.getGenre().name() : null);
            m.put("name", t.getName());
            m.put("systemPrompt", t.getSystemPrompt());
            m.put("template", t.getTemplate());
            return m;
        }).toList();
        ObjectMapper mapper = new ObjectMapper();
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(exportData);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"prompt-templates.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/import")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> importTemplates(@RequestParam("file") MultipartFile file,
                                                               @RequestParam(defaultValue = "overwrite") String mode) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> data = mapper.readValue(file.getBytes(), new TypeReference<>() {});
        int imported = 0;
        for (Map<String, Object> item : data) {
            WorkflowStep step = WorkflowStep.valueOf((String) item.get("step"));
            PromptSubStep subStep = item.get("subStep") != null ? PromptSubStep.valueOf((String) item.get("subStep")) : null;
            Genre genre = item.get("genre") != null ? Genre.valueOf((String) item.get("genre")) : null;
            String name = (String) item.get("name");
            String systemPrompt = (String) item.get("systemPrompt");
            String template = (String) item.get("template");

            PromptTemplateEntity entity = null;
            if ("overwrite".equals(mode)) {
                entity = repository.findAll().stream()
                        .filter(t -> t.getStep() == step
                                && java.util.Objects.equals(t.getSubStep(), subStep)
                                && java.util.Objects.equals(t.getGenre(), genre)
                                && name.equals(t.getName()))
                        .findFirst().orElse(null);
            }
            if (entity == null) {
                entity = new PromptTemplateEntity();
                entity.setStep(step);
                entity.setSubStep(subStep);
                entity.setGenre(genre);
                entity.setName(name);
                entity.setDefault(false);
            }
            entity.setSystemPrompt(systemPrompt);
            entity.setTemplate(template);
            repository.save(entity);
            imported++;
        }
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    @PostMapping
    public String create(@RequestParam WorkflowStep step,
                        @RequestParam(required = false) Genre genre,
                        @RequestParam(required = false) PromptSubStep subStep,
                        @RequestParam String name,
                        @RequestParam(required = false) String systemPrompt,
                        @RequestParam String template) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setStep(step);
        entity.setGenre(genre);
        entity.setSubStep(subStep);
        entity.setName(name);
        entity.setSystemPrompt(systemPrompt);
        entity.setTemplate(template);
        entity.setDefault(false);
        repository.save(entity);
        return "redirect:/prompts";
    }
}
