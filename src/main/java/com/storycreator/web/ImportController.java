package com.storycreator.web;

import com.storycreator.export.ImportService;
import com.storycreator.export.ProjectJsonDto;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/import")
    public String importPage() {
        return "import";
    }

    @PostMapping("/import")
    public String handleImport(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "projectName", required = false) String projectName,
                              @RequestParam(value = "importMode", defaultValue = "new") String importMode,
                              RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "请选择要导入的文件");
            return "redirect:/import";
        }

        try {
            byte[] data = file.getBytes();
            ProjectJsonDto dto = importService.parseJson(data);
            boolean overwrite = "overwrite".equals(importMode);
            Long projectId = importService.importProject(dto, projectName, overwrite);
            redirectAttributes.addFlashAttribute("success", "项目导入成功");
            return "redirect:/projects/" + projectId + "/workflow";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "导入失败: " + e.getMessage());
            return "redirect:/import";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "导入失败: " + e.getMessage());
            return "redirect:/import";
        }
    }
}
