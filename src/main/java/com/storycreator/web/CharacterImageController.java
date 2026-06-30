package com.storycreator.web;

import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.ModelType;
import com.storycreator.image.CharacterImageService;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import com.storycreator.persistence.entity.CharacterImageEntity;
import com.storycreator.persistence.repository.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/projects/{projectId}")
public class CharacterImageController {

    private static final Logger log = LoggerFactory.getLogger(CharacterImageController.class);

    private final CharacterImageService imageService;
    private final ImageProviderRegistry imageProviderRegistry;
    private final AiModelConfigRepository modelConfigRepository;

    public CharacterImageController(CharacterImageService imageService,
                                    ImageProviderRegistry imageProviderRegistry,
                                    AiModelConfigRepository modelConfigRepository) {
        this.imageService = imageService;
        this.imageProviderRegistry = imageProviderRegistry;
        this.modelConfigRepository = modelConfigRepository;
    }

    @PostMapping("/characters/{charId}/images/create")
    public ResponseEntity<Map<String, Object>> createImageRecord(
            @PathVariable Long projectId,
            @PathVariable Long charId,
            @RequestParam(defaultValue = "AVATAR") String imageType,
            @RequestParam(required = false) Long imageConfigId,
            @RequestParam(required = false) Long textConfigId) {
        try {
            ImageType type = ImageType.valueOf(imageType);
            CharacterImageEntity image = imageService.createImageRecord(projectId, charId, type, imageConfigId, textConfigId);
            return ResponseEntity.ok(buildImageMap(image, projectId));
        } catch (Exception e) {
            log.error("Failed to create image record for character {}", charId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", truncateMsg(e.getMessage())));
        }
    }

    @PostMapping("/characters/{charId}/images/{imageId}/generate-prompt")
    public ResponseEntity<Map<String, Object>> generatePrompt(
            @PathVariable Long projectId,
            @PathVariable Long charId,
            @PathVariable Long imageId,
            @RequestParam(required = false) Long textConfigId) {
        try {
            CharacterImageEntity image = imageService.generatePromptForRecord(projectId, charId, imageId, textConfigId);
            return ResponseEntity.ok(buildImageMap(image, projectId));
        } catch (Exception e) {
            log.error("Prompt generation failed for image {}", imageId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", "生成提示词失败: " + truncateMsg(e.getMessage())));
        }
    }

    @PostMapping("/characters/{charId}/images/{imageId}/generate-image")
    public ResponseEntity<Map<String, Object>> generateImage(
            @PathVariable Long projectId,
            @PathVariable Long charId,
            @PathVariable Long imageId,
            @RequestParam(required = false) String prompt,
            @RequestParam(required = false) Long imageConfigId,
            @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height) {
        try {
            CharacterImageEntity image = imageService.generateImageForRecord(projectId, charId, imageId, prompt, imageConfigId, width, height);
            return ResponseEntity.ok(buildImageMap(image, projectId));
        } catch (Exception e) {
            log.error("Image generation failed for image {}", imageId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", "生成图片失败: " + truncateMsg(e.getMessage())));
        }
    }

    @PostMapping("/characters/{charId}/images/{imageId}/update-prompt")
    public ResponseEntity<Map<String, Object>> updatePrompt(
            @PathVariable Long projectId,
            @PathVariable Long charId,
            @PathVariable Long imageId,
            @RequestParam String prompt) {
        try {
            CharacterImageEntity image = imageService.updatePrompt(imageId, projectId, prompt);
            return ResponseEntity.ok(buildImageMap(image, projectId));
        } catch (Exception e) {
            log.error("Failed to update prompt for image {}", imageId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", "保存失败: " + truncateMsg(e.getMessage())));
        }
    }

    @PostMapping("/characters/{charId}/images/generate")
    public ResponseEntity<Map<String, Object>> generateImageLegacy(
            @PathVariable Long projectId,
            @PathVariable Long charId,
            @RequestParam String imageType,
            @RequestParam(required = false) Long configId) {
        try {
            ImageType type = ImageType.valueOf(imageType);
            CharacterImageEntity image = imageService.generateAndSaveImage(projectId, charId, type, configId);
            return ResponseEntity.ok(buildImageMap(image, projectId));
        } catch (Exception e) {
            log.error("Image generation failed for character {}", charId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", "生成失败: " + truncateMsg(e.getMessage())));
        }
    }

    @GetMapping("/characters/{charId}/images")
    public ResponseEntity<List<Map<String, Object>>> getCharacterImages(
            @PathVariable Long projectId,
            @PathVariable Long charId) {
        List<CharacterImageEntity> images = imageService.getCharacterImages(charId);
        List<Map<String, Object>> result = images.stream()
                .map(img -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", img.getId());
                    map.put("imageType", img.getImageType());
                    map.put("imagePrompt", img.getImagePrompt() != null ? img.getImagePrompt() : "");
                    map.put("sourcePrompt", img.getSourcePrompt() != null ? img.getSourcePrompt() : "");
                    map.put("status", img.getStatus());
                    map.put("hasImage", img.getFilePath() != null);
                    map.put("textConfigId", img.getTextConfigId());
                    map.put("imageConfigId", img.getImageConfigId());
                    map.put("createdAt", img.getCreatedAt().toString());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> serveImage(
            @PathVariable Long projectId,
            @PathVariable Long imageId) {
        try {
            CharacterImageEntity image = imageService.getImage(imageId);
            if (image == null || !image.getProjectId().equals(projectId)) {
                return ResponseEntity.notFound().build();
            }
            if (image.getFilePath() == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = imageService.getImageBytes(image);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                    .body(data);
        } catch (Exception e) {
            log.error("Failed to serve image {}", imageId, e);
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/images/{imageId}/delete")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable Long projectId,
            @PathVariable Long imageId) {
        try {
            CharacterImageEntity image = imageService.getImage(imageId);
            if (image == null || !image.getProjectId().equals(projectId)) {
                return ResponseEntity.ok(Map.of("success", false, "message", "Image not found"));
            }
            imageService.deleteImage(imageId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Failed to delete image {}", imageId, e);
            return ResponseEntity.ok(Map.of("success", false, "message", "删除失败: " + e.getMessage()));
        }
    }

    @GetMapping("/image-configs")
    public ResponseEntity<List<Map<String, Object>>> getImageConfigs(@PathVariable Long projectId) {
        List<AiModelConfigEntity> configs = imageProviderRegistry.getActiveImageConfigs();
        Long defaultId = imageProviderRegistry.getGlobalDefaultImageConfigId();
        List<Map<String, Object>> result = configs.stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "displayName", c.getDisplayName() != null ? c.getDisplayName() : c.getModelId(),
                        "modelId", c.getModelId(),
                        "isDefault", c.getId().equals(defaultId)
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/text-configs")
    public ResponseEntity<List<Map<String, Object>>> getTextConfigs(@PathVariable Long projectId) {
        List<AiModelConfigEntity> configs = modelConfigRepository.findByActiveTrueAndModelType(ModelType.TEXT);
        List<Map<String, Object>> result = configs.stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "displayName", c.getDisplayName() != null ? c.getDisplayName() : c.getModelId(),
                        "modelId", c.getModelId()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildImageMap(CharacterImageEntity img, Long projectId) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", true);
        map.put("id", img.getId());
        map.put("imageType", img.getImageType());
        map.put("imagePrompt", img.getImagePrompt() != null ? img.getImagePrompt() : "");
        map.put("sourcePrompt", img.getSourcePrompt() != null ? img.getSourcePrompt() : "");
        map.put("status", img.getStatus());
        map.put("hasImage", img.getFilePath() != null);
        map.put("textConfigId", img.getTextConfigId());
        map.put("imageConfigId", img.getImageConfigId());
        return map;
    }

    private String truncateMsg(String msg) {
        if (msg == null) return "Unknown error";
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
