package com.storycreator.image;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.ImageType;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.core.port.ai.AiRequest;
import com.storycreator.core.port.image.ImageRequest;
import com.storycreator.core.port.image.ImageResult;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.CharacterImageEntity;
import com.storycreator.persistence.repository.CharacterImageRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CharacterImageService {

    private static final Logger log = LoggerFactory.getLogger(CharacterImageService.class);
    private static final String IMAGE_BASE_DIR = "data/images";

    private final CharacterImageRepository imageRepository;
    private final CharacterRepository characterRepository;
    private final ImageProviderRegistry imageProviderRegistry;
    private final AiProviderRouter aiProviderRouter;
    private final PromptTemplateRegistry promptRegistry;
    private final ProjectRepository projectRepository;

    public CharacterImageService(CharacterImageRepository imageRepository,
                                 CharacterRepository characterRepository,
                                 ImageProviderRegistry imageProviderRegistry,
                                 AiProviderRouter aiProviderRouter,
                                 PromptTemplateRegistry promptRegistry,
                                 ProjectRepository projectRepository) {
        this.imageRepository = imageRepository;
        this.characterRepository = characterRepository;
        this.imageProviderRegistry = imageProviderRegistry;
        this.aiProviderRouter = aiProviderRouter;
        this.promptRegistry = promptRegistry;
        this.projectRepository = projectRepository;
    }

    /**
     * Step 0: Create a PROMPT_PENDING image record.
     */
    public CharacterImageEntity createImageRecord(Long projectId, Long characterId, ImageType imageType,
                                                   Long imageConfigId, Long textConfigId) {
        CharacterEntity character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));
        if (!character.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("Character does not belong to project");
        }

        CharacterImageEntity entity = new CharacterImageEntity();
        entity.setCharacterId(characterId);
        entity.setProjectId(projectId);
        entity.setImageType(imageType.name());
        entity.setImageConfigId(imageConfigId);
        entity.setTextConfigId(textConfigId);
        entity.setStatus("PROMPT_PENDING");

        // Pre-fill prompt from character template ONLY for AVATAR type
        if (imageType == ImageType.AVATAR && character.getImagePromptTemplate() != null && !character.getImagePromptTemplate().isBlank()) {
            entity.setImagePrompt(character.getImagePromptTemplate());
            entity.setStatus("PROMPT_READY");
        }

        return imageRepository.save(entity);
    }

    /**
     * Step 1: Generate English image prompt via text AI, save to record and character template.
     */
    public CharacterImageEntity generatePromptForRecord(Long projectId, Long characterId, Long imageId, Long textConfigIdOverride) {
        CharacterImageEntity image = imageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image record not found: " + imageId));
        if (!image.getProjectId().equals(projectId) || !image.getCharacterId().equals(characterId)) {
            throw new IllegalArgumentException("Image record does not match project/character");
        }

        CharacterEntity character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));

        // Resolve text model: override > record > global default
        Long textConfigId = textConfigIdOverride != null ? textConfigIdOverride :
                (image.getTextConfigId() != null ? image.getTextConfigId() : null);

        AiProviderRouter.ResolvedModel textModel;
        if (textConfigId != null) {
            textModel = aiProviderRouter.resolveModelByConfigId(textConfigId);
            if (textModel == null) {
                textModel = aiProviderRouter.resolveModel(projectId, null);
            }
        } else {
            textModel = aiProviderRouter.resolveModel(projectId, null);
        }

        ImageType imageType = ImageType.valueOf(image.getImageType());
        Genre genre = projectRepository.findById(projectId)
                .map(p -> p.getGenre()).orElse(null);
        String imagePrompt = generateImagePrompt(character, imageType, textModel, genre);
        log.info("Generated image prompt for character {}: {}", character.getName(),
                imagePrompt.substring(0, Math.min(100, imagePrompt.length())));

        image.setImagePrompt(imagePrompt);
        image.setStatus("PROMPT_READY");
        image.setErrorMessage(null);
        if (textConfigId != null) {
            image.setTextConfigId(textConfigId);
        }
        imageRepository.save(image);

        // Save as character template only for AVATAR type
        ImageType imgType = ImageType.valueOf(image.getImageType());
        if (imgType == ImageType.AVATAR && (character.getImagePromptTemplate() == null || character.getImagePromptTemplate().isBlank())) {
            character.setImagePromptTemplate(imagePrompt);
            characterRepository.save(character);
        }

        return image;
    }

    /**
     * Step 2: Generate image from prompt via image AI, save file.
     */
    public CharacterImageEntity generateImageForRecord(Long projectId, Long characterId, Long imageId,
                                                        String promptOverride, Long imageConfigIdOverride,
                                                        Integer widthOverride, Integer heightOverride) {
        CharacterImageEntity image = imageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image record not found: " + imageId));
        if (!image.getProjectId().equals(projectId) || !image.getCharacterId().equals(characterId)) {
            throw new IllegalArgumentException("Image record does not match project/character");
        }

        // Use provided prompt or fall back to stored prompt
        String prompt = (promptOverride != null && !promptOverride.isBlank()) ? promptOverride : image.getImagePrompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("No prompt available. Generate or provide a prompt first.");
        }

        // If prompt was overridden, update it on the record
        if (promptOverride != null && !promptOverride.isBlank()) {
            image.setImagePrompt(promptOverride);
        }

        image.setStatus("IMAGE_GENERATING");
        imageRepository.save(image);

        // Resolve image config
        Long configId = imageConfigIdOverride != null ? imageConfigIdOverride :
                (image.getImageConfigId() != null ? image.getImageConfigId() : null);

        ImageProviderRegistry.ResolvedImageConfig imageConfig;
        if (configId != null) {
            imageConfig = imageProviderRegistry.resolve(configId);
        } else {
            imageConfig = imageProviderRegistry.resolveGlobalDefault();
        }
        if (imageConfig == null) {
            image.setStatus("ERROR");
            image.setErrorMessage("No IMAGE model configuration available");
            imageRepository.save(image);
            throw new IllegalStateException("No IMAGE model configuration available. Please add one in Settings.");
        }

        // Determine dimensions
        ImageType imageType = ImageType.valueOf(image.getImageType());
        int width = (widthOverride != null && widthOverride > 0) ? widthOverride : 1024;
        int height = (heightOverride != null && heightOverride > 0) ? heightOverride : 1024;
        if (widthOverride == null && heightOverride == null && imageType == ImageType.PORTRAIT) {
            height = 1792;
        }

        // Call image AI
        ImageRequest imageRequest = ImageRequest.builder()
                .model(imageConfig.modelId())
                .prompt(prompt)
                .imageType(imageType)
                .width(width)
                .height(height)
                .baseUrl(imageConfig.baseUrl())
                .apiKey(imageConfig.apiKey())
                .extraParams(imageConfig.extraParams())
                .build();

        ImageResult result;
        try {
            result = imageConfig.provider().generateImage(imageRequest);
        } catch (Exception e) {
            image.setStatus("ERROR");
            image.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "Image generation failed");
            imageRepository.save(image);
            throw e;
        }

        // Save file
        String fileName = UUID.randomUUID().toString() + ".png";
        String relativePath = projectId + "/" + characterId + "/" + fileName;
        Path fullPath = Paths.get(IMAGE_BASE_DIR, relativePath);

        try {
            Files.createDirectories(fullPath.getParent());
            Files.write(fullPath, result.imageBytes());
        } catch (IOException e) {
            image.setStatus("ERROR");
            image.setErrorMessage("Failed to save image file: " + e.getMessage());
            imageRepository.save(image);
            throw new RuntimeException("Failed to save image file: " + e.getMessage(), e);
        }

        // Update record
        image.setFilePath(relativePath);
        image.setSourcePrompt(result.revisedPrompt());
        image.setModelConfigId(imageConfig.configId());
        if (configId != null) {
            image.setImageConfigId(configId);
        }
        image.setStatus("READY");
        image.setErrorMessage(null);
        return imageRepository.save(image);
    }

    /**
     * Update the prompt text on an image record (user manual edit).
     */
    public CharacterImageEntity updatePrompt(Long imageId, Long projectId, String prompt) {
        CharacterImageEntity image = imageRepository.findById(imageId)
                .orElseThrow(() -> new IllegalArgumentException("Image record not found: " + imageId));
        if (!image.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("Image does not belong to project");
        }
        image.setImagePrompt(prompt);
        if (prompt != null && !prompt.isBlank()) {
            image.setStatus("PROMPT_READY");
        }
        return imageRepository.save(image);
    }

    /**
     * Legacy single-call method (kept for backward compatibility).
     */
    public CharacterImageEntity generateAndSaveImage(Long projectId, Long characterId, ImageType imageType, Long imageConfigId) {
        CharacterImageEntity record = createImageRecord(projectId, characterId, imageType, imageConfigId, null);
        generatePromptForRecord(projectId, characterId, record.getId(), null);
        return generateImageForRecord(projectId, characterId, record.getId(), null, imageConfigId, null, null);
    }

    public Map<String, String> buildImagePromptVariables(Long projectId, Long characterId, ImageType imageType) {
        CharacterEntity character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found: " + characterId));
        if (!character.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("Character does not belong to project");
        }
        Map<String, String> variables = new HashMap<>();
        variables.put("gender", character.getGender() != null ? character.getGender() : "");
        variables.put("age", character.getAge() != null ? character.getAge() : "");
        variables.put("appearance", character.getAppearance() != null ? character.getAppearance() : "");
        variables.put("personality", character.getPersonality() != null ? character.getPersonality() : "");
        variables.put("role", character.getRole() != null ? character.getRole() : "");
        if (imageType == ImageType.PORTRAIT) {
            variables.put("background", character.getBackground() != null ? character.getBackground() : "");
            variables.put("motivation", character.getMotivation() != null ? character.getMotivation() : "");
        }
        return variables;
    }

    private String generateImagePrompt(CharacterEntity character, ImageType imageType,
                                       AiProviderRouter.ResolvedModel textModel, Genre genre) {
        PromptSubStep subStep = imageType == ImageType.AVATAR
                ? PromptSubStep.IMAGE_PROMPT_AVATAR
                : PromptSubStep.IMAGE_PROMPT_PORTRAIT;

        Map<String, String> variables = new HashMap<>();
        variables.put("gender", character.getGender() != null ? character.getGender() : "");
        variables.put("age", character.getAge() != null ? character.getAge() : "");
        variables.put("appearance", character.getAppearance() != null ? character.getAppearance() : "");
        variables.put("personality", character.getPersonality() != null ? character.getPersonality() : "");
        variables.put("role", character.getRole() != null ? character.getRole() : "");
        if (imageType == ImageType.PORTRAIT) {
            variables.put("background", character.getBackground() != null ? character.getBackground() : "");
            variables.put("motivation", character.getMotivation() != null ? character.getMotivation() : "");
        }

        String template = promptRegistry.getSubStepTemplate(
                WorkflowStep.CHARACTER_DESIGN, subStep, genre);
        String systemPrompt = promptRegistry.getSubStepSystemPrompt(
                WorkflowStep.CHARACTER_DESIGN, subStep, genre);
        String userPrompt = promptRegistry.resolveTemplate(template, variables);

        AiRequest request = AiRequest.builder()
                .model(textModel.modelId())
                .baseUrl(textModel.baseUrl())
                .apiKey(textModel.apiKey())
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(500)
                .temperature(0.7)
                .build();

        // Use streamText + collect to work around ClaudeAiProvider.generateText() bug
        String result = textModel.provider().streamText(request)
                .collectList()
                .map(tokens -> String.join("", tokens))
                .block();

        if (result == null || result.isBlank()) {
            throw new RuntimeException("Text AI returned empty prompt");
        }

        return result.trim();
    }

    public List<CharacterImageEntity> getCharacterImages(Long characterId) {
        return imageRepository.findByCharacterIdOrderByCreatedAtDesc(characterId);
    }

    public CharacterImageEntity getImage(Long imageId) {
        return imageRepository.findById(imageId).orElse(null);
    }

    public byte[] getImageBytes(CharacterImageEntity image) throws IOException {
        if (image.getFilePath() == null) {
            throw new IOException("Image file not yet generated");
        }
        Path fullPath = Paths.get(IMAGE_BASE_DIR, image.getFilePath());
        if (!Files.exists(fullPath)) {
            throw new IOException("Image file not found: " + image.getFilePath());
        }
        return Files.readAllBytes(fullPath);
    }

    public void deleteImage(Long imageId) {
        CharacterImageEntity image = imageRepository.findById(imageId).orElse(null);
        if (image == null) return;

        // Delete file if exists
        if (image.getFilePath() != null) {
            Path fullPath = Paths.get(IMAGE_BASE_DIR, image.getFilePath());
            try {
                Files.deleteIfExists(fullPath);
            } catch (IOException e) {
                log.warn("Failed to delete image file: {}", fullPath, e);
            }
        }

        imageRepository.delete(image);
    }
}
