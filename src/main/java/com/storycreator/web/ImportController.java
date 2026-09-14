package com.storycreator.web;

import com.storycreator.export.ImportService;
import com.storycreator.export.ProjectJsonDto;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Controller
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/import")
    public String importPage() {
        return "forward:/pages/import.html";
    }

    @PostMapping("/import")
    public String handleImport(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "projectName", required = false) String projectName,
                              @RequestParam(value = "importMode", defaultValue = "new") String importMode) {
        if (file.isEmpty()) {
            return "redirect:/import" + errorQuery("请选择要导入的文件");
        }

        try {
            byte[] data = file.getBytes();
            ProjectJsonDto dto = importService.parseJson(data);
            boolean overwrite = "overwrite".equals(importMode);
            Long projectId = importService.importProject(dto, projectName, overwrite);
            return "redirect:/projects/" + projectId + "/workflow?imported=1";
        } catch (Exception e) {
            return "redirect:/import" + errorQuery("导入失败: " + e.getMessage());
        }
    }

    /** 静态页无法读取 flash attribute，改为把错误信息放进查询串 */
    private static String errorQuery(String message) {
        return "?error=" + UriUtils.encode(message, StandardCharsets.UTF_8);
    }
}
