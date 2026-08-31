package com.storycreator.web;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.txtimport.ChapterSplitConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/settings/chapter-split-configs")
public class ChapterSplitConfigController {

    private final ChapterSplitConfigService configService;

    public ChapterSplitConfigController(ChapterSplitConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("configs", configService.listAll());
        return "chapter-split-configs";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam String description,
                         @RequestParam String pattern,
                         @RequestParam(defaultValue = "0") int titleGroup,
                         @RequestParam(defaultValue = "false") boolean includeMatch) {
        configService.create(name, description, pattern, titleGroup, includeMatch);
        return "redirect:/settings/chapter-split-configs";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam String description,
                         @RequestParam String pattern,
                         @RequestParam(defaultValue = "0") int titleGroup,
                         @RequestParam(defaultValue = "false") boolean includeMatch) {
        configService.update(id, name, description, pattern, titleGroup, includeMatch);
        return "redirect:/settings/chapter-split-configs";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id) {
        configService.toggle(id);
        return "redirect:/settings/chapter-split-configs";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        configService.delete(id);
        return "redirect:/settings/chapter-split-configs";
    }

    @PostMapping("/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> test(@RequestBody Map<String, String> body) {
        String pattern = body.get("pattern");
        String sampleText = body.get("sampleText");
        if (pattern == null || sampleText == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供正则表达式和示例文本"));
        }
        try {
            var result = configService.testPattern(pattern, sampleText);
            return ResponseEntity.ok(Map.of(
                    "matchCount", result.matchCount(),
                    "matches", result.matches()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
