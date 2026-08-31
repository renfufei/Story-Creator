package com.storycreator.web;

import com.storycreator.core.domain.Genre;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import com.storycreator.txtimport.ChapterSplitConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/import/txt")
public class TxtImportPageController {

    private final ChapterSplitConfigService configService;
    private final AiModelConfigRepository aiModelConfigRepository;

    public TxtImportPageController(ChapterSplitConfigService configService,
                                   AiModelConfigRepository aiModelConfigRepository) {
        this.configService = configService;
        this.aiModelConfigRepository = aiModelConfigRepository;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("splitConfigs", configService.listEnabled());
        model.addAttribute("genres", Genre.values());
        model.addAttribute("modelConfigs", aiModelConfigRepository.findAll());
        return "txt-import";
    }
}
