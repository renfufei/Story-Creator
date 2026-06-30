package com.storycreator.web;

import com.storycreator.ai.prompt.BuiltinTemplate;
import com.storycreator.ai.prompt.BuiltinTemplateLoader;
import com.storycreator.ai.prompt.PromptTemplateRegistry;
import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptControllerTest {

    @Mock private PromptTemplateRepository repository;
    @Mock private PromptTemplateRegistry promptRegistry;
    @Mock private BuiltinTemplateLoader builtinLoader;

    private PromptController controller;

    @BeforeEach
    void setUp() {
        controller = new PromptController(repository, promptRegistry, builtinLoader);
    }

    @Test
    void create_savesNewTemplateWithDefaultFalse() {
        ArgumentCaptor<PromptTemplateEntity> captor = ArgumentCaptor.forClass(PromptTemplateEntity.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.create(WorkflowStep.WORLD_BUILDING, Genre.XUANHUAN,
                null, "新模板", "系统提示", "模板内容");

        verify(repository).save(captor.capture());
        PromptTemplateEntity saved = captor.getValue();
        assertThat(saved.getStep()).isEqualTo(WorkflowStep.WORLD_BUILDING);
        assertThat(saved.getGenre()).isEqualTo(Genre.XUANHUAN);
        assertThat(saved.getSubStep()).isNull();
        assertThat(saved.getName()).isEqualTo("新模板");
        assertThat(saved.getSystemPrompt()).isEqualTo("系统提示");
        assertThat(saved.getTemplate()).isEqualTo("模板内容");
        assertThat(saved.isDefault()).isFalse();
    }

    @Test
    void update_updatesExistingTemplate() {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setName("旧名");
        entity.setTemplate("旧模板");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.update(1L, "新名", "新系统提示", "新模板");

        assertThat(entity.getName()).isEqualTo("新名");
        assertThat(entity.getSystemPrompt()).isEqualTo("新系统提示");
        assertThat(entity.getTemplate()).isEqualTo("新模板");
        verify(repository).save(entity);
    }

    @Test
    void update_templateNotFound_throwsException() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.update(999L, "n", "s", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template not found");
    }

    @Test
    void reset_deletesTemplate() {
        controller.reset(5L);

        verify(repository).deleteById(5L);
    }

    @Test
    void setDefault_unsetsOthersAndSetsTarget() {
        PromptTemplateEntity target = new PromptTemplateEntity();
        target.setStep(WorkflowStep.WORLD_BUILDING);
        target.setSubStep(null);
        target.setGenre(null);
        target.setDefault(false);
        when(repository.findById(1L)).thenReturn(Optional.of(target));

        // Another template with same step/subStep/genre that is currently default
        PromptTemplateEntity other = new PromptTemplateEntity();
        other.setStep(WorkflowStep.WORLD_BUILDING);
        other.setSubStep(null);
        other.setGenre(null);
        other.setDefault(true);

        when(repository.findAll()).thenReturn(List.of(target, other));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.setDefault(1L);

        // 'other' should be unset
        assertThat(other.isDefault()).isFalse();
        // 'target' should be set as default
        assertThat(target.isDefault()).isTrue();
    }

    @Test
    void unsetDefault_marksTemplateNonDefault() {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setDefault(true);
        when(repository.findById(2L)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.unsetDefault(2L);

        assertThat(entity.isDefault()).isFalse();
        verify(repository).save(entity);
    }

    @Test
    void delete_removesTemplateAndReturnsSuccess() {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        when(repository.findById(3L)).thenReturn(Optional.of(entity));

        ResponseEntity<Map<String, Object>> response = controller.delete(3L);

        verify(repository).delete(entity);
        assertThat(response.getBody()).containsEntry("success", true);
    }

    @Test
    void exportTemplates_returnsJsonWithCorrectFields() throws Exception {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setStep(WorkflowStep.CHARACTER_DESIGN);
        entity.setSubStep(PromptSubStep.CHARACTER_CARD);
        entity.setGenre(Genre.XUANHUAN);
        entity.setName("导出模板");
        entity.setSystemPrompt("sys");
        entity.setTemplate("tmpl");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(entity));

        ResponseEntity<byte[]> response = controller.exportTemplates(List.of(1L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String json = new String(response.getBody());
        assertThat(json).contains("\"step\" : \"CHARACTER_DESIGN\"");
        assertThat(json).contains("\"subStep\" : \"CHARACTER_CARD\"");
        assertThat(json).contains("\"genre\" : \"XUANHUAN\"");
        assertThat(json).contains("\"name\" : \"导出模板\"");
        assertThat(json).contains("\"systemPrompt\" : \"sys\"");
        assertThat(json).contains("\"template\" : \"tmpl\"");
    }

    @Test
    void importTemplates_overwriteMode_matchesExisting() throws Exception {
        String json = """
                [{"step":"WORLD_BUILDING","subStep":null,"genre":null,"name":"模板A","systemPrompt":"新sys","template":"新tmpl"}]
                """;
        MockMultipartFile file = new MockMultipartFile("file", "templates.json",
                "application/json", json.getBytes());

        PromptTemplateEntity existing = new PromptTemplateEntity();
        existing.setStep(WorkflowStep.WORLD_BUILDING);
        existing.setSubStep(null);
        existing.setGenre(null);
        existing.setName("模板A");
        existing.setTemplate("旧tmpl");
        when(repository.findAll()).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.importTemplates(file, "overwrite");

        assertThat(response.getBody()).containsEntry("imported", 1);
        // Should update existing entity
        assertThat(existing.getTemplate()).isEqualTo("新tmpl");
        assertThat(existing.getSystemPrompt()).isEqualTo("新sys");
    }

    @Test
    void importTemplates_newMode_createsNew() throws Exception {
        String json = """
                [{"step":"POLISHING","subStep":null,"genre":"DUSHI","name":"新模板","systemPrompt":"s","template":"t"}]
                """;
        MockMultipartFile file = new MockMultipartFile("file", "templates.json",
                "application/json", json.getBytes());

        // No match in overwrite mode (different name)
        when(repository.findAll()).thenReturn(List.of());
        ArgumentCaptor<PromptTemplateEntity> captor = ArgumentCaptor.forClass(PromptTemplateEntity.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.importTemplates(file, "overwrite");

        verify(repository).save(captor.capture());
        PromptTemplateEntity created = captor.getValue();
        assertThat(created.getStep()).isEqualTo(WorkflowStep.POLISHING);
        assertThat(created.getGenre()).isEqualTo(Genre.DUSHI);
        assertThat(created.getName()).isEqualTo("新模板");
        assertThat(created.isDefault()).isFalse();
    }

    @Test
    void viewBuiltinJson_returnsTemplateAndSystemPrompt() {
        BuiltinTemplate bt = new BuiltinTemplate("WORLD_BUILDING||", WorkflowStep.WORLD_BUILDING,
                null, null, "builtin", "内置系统提示", "内置模板");
        when(builtinLoader.getAll()).thenReturn(List.of(bt));

        ResponseEntity<Map<String, String>> response = controller.viewBuiltinJson("WORLD_BUILDING||");

        assertThat(response.getBody()).containsEntry("template", "内置模板");
        assertThat(response.getBody()).containsEntry("systemPrompt", "内置系统提示");
    }

    @Test
    void viewCustomJson_returnsTemplateAndSystemPrompt() {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setTemplate("自定义模板");
        entity.setSystemPrompt("自定义系统");
        when(repository.findById(7L)).thenReturn(Optional.of(entity));

        ResponseEntity<Map<String, String>> response = controller.viewCustomJson(7L);

        assertThat(response.getBody()).containsEntry("template", "自定义模板");
        assertThat(response.getBody()).containsEntry("systemPrompt", "自定义系统");
    }

    @Test
    void hasCustomDefault_checksCorrectRepositoryMethod() {
        // Verify indirectly via list() — when a custom template is default for same step,
        // the builtin's isDefault becomes false
        BuiltinTemplate bt = new BuiltinTemplate("WORLD_BUILDING||", WorkflowStep.WORLD_BUILDING,
                null, null, "builtin", null, "tmpl");
        when(builtinLoader.getAll()).thenReturn(List.of(bt));

        PromptTemplateEntity customDefault = new PromptTemplateEntity();
        customDefault.setStep(WorkflowStep.WORLD_BUILDING);
        customDefault.setDefault(true);
        when(repository.findAll()).thenReturn(List.of(customDefault));

        // When custom default exists for same step+genre(null)
        when(repository.findByStepAndGenreIsNullAndIsDefaultTrue(WorkflowStep.WORLD_BUILDING))
                .thenReturn(Optional.of(customDefault));

        org.springframework.ui.Model model = mock(org.springframework.ui.Model.class);
        controller.list(model);

        // The builtin template should have isDefault=false (because custom override exists)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(model).addAttribute(eq("templates"), captor.capture());
        @SuppressWarnings("unchecked")
        List<PromptController.TemplateListItem> items = (List<PromptController.TemplateListItem>) captor.getValue();
        // First item is the builtin
        assertThat(items.get(0).builtin()).isTrue();
        assertThat(items.get(0).isDefault()).isFalse();
    }
}
