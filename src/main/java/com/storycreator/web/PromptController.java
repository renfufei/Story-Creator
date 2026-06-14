package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/prompts")
public class PromptController {

    private final PromptTemplateRepository repository;

    public PromptController(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", repository.findAll());
        model.addAttribute("steps", WorkflowStep.values());
        model.addAttribute("genres", Genre.values());
        return "prompts";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        PromptTemplateEntity template = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        model.addAttribute("template", template);
        model.addAttribute("steps", WorkflowStep.values());
        model.addAttribute("genres", Genre.values());
        return "prompt-edit";
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
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        if (entity.getDefaultTemplate() != null) {
            entity.setTemplate(entity.getDefaultTemplate());
        }
        if (entity.getDefaultSystemPrompt() != null) {
            entity.setSystemPrompt(entity.getDefaultSystemPrompt());
        }
        repository.save(entity);
        return "redirect:/prompts";
    }

    @PostMapping("/{id}/set-default")
    public String setDefault(@PathVariable Long id) {
        PromptTemplateEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        // Unset default for all templates with same step and genre
        repository.findAll().stream()
                .filter(t -> t.getStep() == entity.getStep()
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

    @PostMapping
    public String create(@RequestParam WorkflowStep step,
                        @RequestParam(required = false) Genre genre,
                        @RequestParam String name,
                        @RequestParam String template) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setStep(step);
        entity.setGenre(genre);
        entity.setName(name);
        entity.setTemplate(template);
        entity.setDefault(false);
        repository.save(entity);
        return "redirect:/prompts";
    }
}
