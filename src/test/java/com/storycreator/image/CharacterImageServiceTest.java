package com.storycreator.image;

import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.ImageProviderRegistry;
import com.storycreator.core.domain.ImageType;
import com.storycreator.persistence.entity.CharacterEntity;
import com.storycreator.persistence.entity.CharacterImageEntity;
import com.storycreator.persistence.repository.CharacterImageRepository;
import com.storycreator.persistence.repository.CharacterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CharacterImageServiceTest {

    @Mock private CharacterImageRepository imageRepository;
    @Mock private CharacterRepository characterRepository;
    @Mock private ImageProviderRegistry imageProviderRegistry;
    @Mock private AiProviderRouter aiProviderRouter;
    @Mock private PromptTemplateRegistry promptRegistry;
    @Mock private ProjectRepository projectRepository;

    private CharacterImageService service;

    @BeforeEach
    void setUp() {
        service = new CharacterImageService(imageRepository, characterRepository,
                imageProviderRegistry, aiProviderRouter, promptRegistry, projectRepository);
    }

    private CharacterEntity makeCharacter(Long charId, Long projectId) {
        CharacterEntity c = new CharacterEntity();
        c.setId(charId);
        c.setProjectId(projectId);
        c.setName("测试角色");
        c.setGender("女");
        c.setAge("20");
        c.setAppearance("银发碧眼");
        c.setPersonality("冷静");
        c.setRole("主角");
        c.setBackground("神秘出身");
        c.setMotivation("寻找真相");
        return c;
    }

    // ==================== createImageRecord ====================

    @Test
    void createImageRecord_avatarWithNoTemplate_statusPromptPending() {
        CharacterEntity c = makeCharacter(1L, 10L);
        c.setImagePromptTemplate(null);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        CharacterImageEntity saved = new CharacterImageEntity();
        saved.setId(100L);
        when(imageRepository.save(any())).thenReturn(saved);

        CharacterImageEntity result = service.createImageRecord(10L, 1L, ImageType.AVATAR, null, null);

        verify(imageRepository).save(argThat(e ->
                "PROMPT_PENDING".equals(e.getStatus())
                && "AVATAR".equals(e.getImageType())
                && e.getCharacterId().equals(1L)
                && e.getProjectId().equals(10L)));
        assertEquals(100L, result.getId());
    }

    @Test
    void createImageRecord_avatarWithTemplate_statusPromptReady() {
        CharacterEntity c = makeCharacter(1L, 10L);
        c.setImagePromptTemplate("a beautiful silver-haired woman");
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        CharacterImageEntity saved = new CharacterImageEntity();
        when(imageRepository.save(any())).thenReturn(saved);

        service.createImageRecord(10L, 1L, ImageType.AVATAR, null, null);

        verify(imageRepository).save(argThat(e ->
                "PROMPT_READY".equals(e.getStatus())
                && "a beautiful silver-haired woman".equals(e.getImagePrompt())));
    }

    @Test
    void createImageRecord_portraitIgnoresTemplate_statusPromptPending() {
        CharacterEntity c = makeCharacter(1L, 10L);
        c.setImagePromptTemplate("some template");  // portrait ignores template
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        CharacterImageEntity saved = new CharacterImageEntity();
        when(imageRepository.save(any())).thenReturn(saved);

        service.createImageRecord(10L, 1L, ImageType.PORTRAIT, null, null);

        verify(imageRepository).save(argThat(e -> "PROMPT_PENDING".equals(e.getStatus())));
    }

    @Test
    void createImageRecord_wrongProject_throwsIllegalArgument() {
        CharacterEntity c = makeCharacter(1L, 99L);  // belongs to project 99
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        assertThrows(IllegalArgumentException.class,
                () -> service.createImageRecord(10L, 1L, ImageType.AVATAR, null, null));
    }

    @Test
    void createImageRecord_characterNotFound_throwsIllegalArgument() {
        when(characterRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createImageRecord(10L, 999L, ImageType.AVATAR, null, null));
    }

    @Test
    void createImageRecord_setsImageAndTextConfigIds() {
        CharacterEntity c = makeCharacter(1L, 10L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));
        when(imageRepository.save(any())).thenReturn(new CharacterImageEntity());

        service.createImageRecord(10L, 1L, ImageType.AVATAR, 55L, 66L);

        verify(imageRepository).save(argThat(e ->
                e.getImageConfigId().equals(55L) && e.getTextConfigId().equals(66L)));
    }

    // ==================== updatePrompt ====================

    @Test
    void updatePrompt_setsPromptAndStatusPromptReady() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(10L);
        img.setStatus("PROMPT_PENDING");
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CharacterImageEntity result = service.updatePrompt(1L, 10L, "new english prompt");

        assertEquals("new english prompt", result.getImagePrompt());
        assertEquals("PROMPT_READY", result.getStatus());
    }

    @Test
    void updatePrompt_blankPromptDoesNotChangeStatus() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(10L);
        img.setStatus("PROMPT_PENDING");
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CharacterImageEntity result = service.updatePrompt(1L, 10L, "   ");

        // Blank prompt should not flip to PROMPT_READY
        assertEquals("PROMPT_PENDING", result.getStatus());
    }

    @Test
    void updatePrompt_wrongProject_throwsIllegalArgument() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(99L);
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));

        assertThrows(IllegalArgumentException.class,
                () -> service.updatePrompt(1L, 10L, "prompt"));
    }

    @Test
    void updatePrompt_imageNotFound_throwsIllegalArgument() {
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.updatePrompt(999L, 10L, "prompt"));
    }

    // ==================== buildImagePromptVariables ====================

    @Test
    void buildImagePromptVariables_avatarDoesNotIncludeBackgroundMotivation() {
        CharacterEntity c = makeCharacter(1L, 10L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        Map<String, String> vars = service.buildImagePromptVariables(10L, 1L, ImageType.AVATAR);

        assertFalse(vars.containsKey("background"));
        assertFalse(vars.containsKey("motivation"));
        assertTrue(vars.containsKey("gender"));
        assertTrue(vars.containsKey("age"));
        assertTrue(vars.containsKey("appearance"));
        assertTrue(vars.containsKey("personality"));
        assertTrue(vars.containsKey("role"));
    }

    @Test
    void buildImagePromptVariables_portraitIncludesBackgroundMotivation() {
        CharacterEntity c = makeCharacter(1L, 10L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        Map<String, String> vars = service.buildImagePromptVariables(10L, 1L, ImageType.PORTRAIT);

        assertTrue(vars.containsKey("background"));
        assertTrue(vars.containsKey("motivation"));
        assertEquals("神秘出身", vars.get("background"));
        assertEquals("寻找真相", vars.get("motivation"));
    }

    @Test
    void buildImagePromptVariables_nullFieldsMappedToEmptyString() {
        CharacterEntity c = makeCharacter(1L, 10L);
        c.setGender(null);
        c.setAge(null);
        c.setAppearance(null);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        Map<String, String> vars = service.buildImagePromptVariables(10L, 1L, ImageType.AVATAR);

        assertEquals("", vars.get("gender"));
        assertEquals("", vars.get("age"));
        assertEquals("", vars.get("appearance"));
    }

    @Test
    void buildImagePromptVariables_wrongProject_throwsIllegalArgument() {
        CharacterEntity c = makeCharacter(1L, 99L);
        when(characterRepository.findById(1L)).thenReturn(Optional.of(c));

        assertThrows(IllegalArgumentException.class,
                () -> service.buildImagePromptVariables(10L, 1L, ImageType.AVATAR));
    }

    // ==================== getCharacterImages ====================

    @Test
    void getCharacterImages_delegatesToRepository() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        when(imageRepository.findByCharacterIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(img));

        List<CharacterImageEntity> result = service.getCharacterImages(5L);

        assertEquals(1, result.size());
        verify(imageRepository).findByCharacterIdOrderByCreatedAtDesc(5L);
    }

    // ==================== getImage ====================

    @Test
    void getImage_returnsEntityWhenFound() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(7L);
        when(imageRepository.findById(7L)).thenReturn(Optional.of(img));

        CharacterImageEntity result = service.getImage(7L);

        assertNotNull(result);
        assertEquals(7L, result.getId());
    }

    @Test
    void getImage_returnsNullWhenNotFound() {
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        assertNull(service.getImage(999L));
    }

    // ==================== deleteImage ====================

    @Test
    void deleteImage_noOpWhenImageNotFound() {
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.deleteImage(999L));
        verify(imageRepository, never()).delete(any());
    }

    @Test
    void deleteImage_deletesEntityWhenNoFilePath() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setFilePath(null);
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));

        service.deleteImage(1L);

        verify(imageRepository).delete(img);
    }

    // ==================== generateImageForRecord - validation ====================

    @Test
    void generateImageForRecord_wrongProject_throwsIllegalArgument() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(99L);
        img.setCharacterId(1L);
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));

        assertThrows(IllegalArgumentException.class, () ->
                service.generateImageForRecord(10L, 1L, 1L, ImageGenerationOptions.none()));
    }

    @Test
    void generateImageForRecord_noPromptAndNoStoredPrompt_throwsIllegalState() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(10L);
        img.setCharacterId(1L);
        img.setImageType("AVATAR");
        img.setImagePrompt(null);
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenReturn(img);

        // No image config available either
        when(imageProviderRegistry.resolveGlobalDefault()).thenReturn(null);

        // Should fail because no prompt available first OR no config
        // The implementation checks prompt first
        assertThrows(IllegalStateException.class, () ->
                service.generateImageForRecord(10L, 1L, 1L, ImageGenerationOptions.none()));
    }

    @Test
    void generateImageForRecord_noImageConfig_setsErrorStatusAndThrows() {
        CharacterImageEntity img = new CharacterImageEntity();
        img.setId(1L);
        img.setProjectId(10L);
        img.setCharacterId(1L);
        img.setImageType("AVATAR");
        img.setImagePrompt("a beautiful character");
        when(imageRepository.findById(1L)).thenReturn(Optional.of(img));
        when(imageRepository.save(any())).thenReturn(img);
        when(imageProviderRegistry.resolveGlobalDefault()).thenReturn(null);

        assertThrows(IllegalStateException.class, () ->
                service.generateImageForRecord(10L, 1L, 1L, ImageGenerationOptions.none()));

        verify(imageRepository, atLeastOnce()).save(argThat(e -> "ERROR".equals(e.getStatus())));
    }

    // ==================== ImageGenerationOptions ====================

    @Test
    void imageGenerationOptions_noneReturnsAllNulls() {
        ImageGenerationOptions opts = ImageGenerationOptions.none();

        assertNull(opts.promptOverride());
        assertNull(opts.imageConfigIdOverride());
        assertNull(opts.widthOverride());
        assertNull(opts.heightOverride());
    }

    @Test
    void imageGenerationOptions_recordFields() {
        ImageGenerationOptions opts = new ImageGenerationOptions("prompt", 5L, 512, 768);

        assertEquals("prompt", opts.promptOverride());
        assertEquals(5L, opts.imageConfigIdOverride());
        assertEquals(512, opts.widthOverride());
        assertEquals(768, opts.heightOverride());
    }
}
