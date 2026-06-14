package com.storycreator.web;

import com.storycreator.export.ExportService;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ProjectRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/projects/{projectId}/export")
public class ExportController {

    private final ExportService exportService;
    private final ProjectRepository projectRepository;

    public ExportController(ExportService exportService, ProjectRepository projectRepository) {
        this.exportService = exportService;
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public ResponseEntity<byte[]> export(@PathVariable Long projectId,
                                         @RequestParam(defaultValue = "markdown") String format) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        String filename = project.getTitle();

        return switch (format.toLowerCase()) {
            case "txt" -> {
                String content = exportService.exportTxt(projectId);
                yield buildResponse(content.getBytes(StandardCharsets.UTF_8),
                        filename + ".txt", "text/plain; charset=UTF-8");
            }
            case "epub" -> {
                byte[] epub = exportService.exportEpub(projectId);
                yield buildResponse(epub, filename + ".epub", "application/epub+zip");
            }
            case "json" -> {
                byte[] json = exportService.exportJson(projectId);
                yield buildResponse(json, filename + ".json", "application/json; charset=UTF-8");
            }
            case "pdf" -> {
                byte[] pdf = exportService.exportPdf(projectId);
                yield buildResponse(pdf, filename + ".pdf", "application/pdf");
            }
            default -> {
                String content = exportService.exportMarkdown(projectId);
                yield buildResponse(content.getBytes(StandardCharsets.UTF_8),
                        filename + ".md", "text/markdown; charset=UTF-8");
            }
        };
    }

    private ResponseEntity<byte[]> buildResponse(byte[] content, String filename, String contentType) {
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}
