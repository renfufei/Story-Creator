package com.storycreator.persistence.repository;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.PromptSubStep;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:repo_test;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class PromptTemplateRepositoryTest {

    @Autowired
    private PromptTemplateRepository repository;

    @Test
    void flywayMigrationsRunSuccessfully() {
        // If we get here without exception, all V1-V29 migrations succeeded
        long count = repository.count();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void v29SubStepTemplatesExist() {
        // V29 inserts 15 sub-step templates
        for (PromptSubStep subStep : PromptSubStep.values()) {
            WorkflowStep parentStep = subStep.getParentStep();
            Optional<PromptTemplateEntity> template = repository
                    .findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(parentStep, subStep);
            assertThat(template)
                    .as("Default template should exist for sub-step %s", subStep.name())
                    .isPresent();
            assertThat(template.get().isDefault()).isTrue();
            assertThat(template.get().getSubStep()).isEqualTo(subStep);
        }
    }

    @Test
    void findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue_returnsCorrectTemplate() {
        Optional<PromptTemplateEntity> result = repository
                .findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(
                        WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD);

        assertThat(result).isPresent();
        assertThat(result.get().getStep()).isEqualTo(WorkflowStep.CHARACTER_DESIGN);
        assertThat(result.get().getSubStep()).isEqualTo(PromptSubStep.CHARACTER_CARD);
        assertThat(result.get().getGenre()).isNull();
        assertThat(result.get().getTemplate()).contains("{{cardNumber}}");
    }

    @Test
    void findByStepAndSubStepAndGenreAndIsDefaultTrue_returnsEmptyWhenNoGenreSpecificTemplate() {
        // V29 only inserts genre=NULL templates, so genre-specific query should return empty
        Optional<PromptTemplateEntity> result = repository
                .findByStepAndSubStepAndGenreAndIsDefaultTrue(
                        WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XUANHUAN);

        assertThat(result).isEmpty();
    }

    @Test
    void findByStepAndSubStepAndGenreAndIsDefaultTrue_returnsTemplateWhenGenreMatches() {
        // Insert a genre-specific template
        PromptTemplateEntity genreTemplate = new PromptTemplateEntity();
        genreTemplate.setStep(WorkflowStep.CHARACTER_DESIGN);
        genreTemplate.setSubStep(PromptSubStep.CHARACTER_CARD);
        genreTemplate.setGenre(Genre.XIANXIA);
        genreTemplate.setName("仙侠角色卡");
        genreTemplate.setTemplate("仙侠专用角色卡模板 {{cardNumber}}");
        genreTemplate.setDefault(true);
        repository.save(genreTemplate);

        Optional<PromptTemplateEntity> result = repository
                .findByStepAndSubStepAndGenreAndIsDefaultTrue(
                        WorkflowStep.CHARACTER_DESIGN, PromptSubStep.CHARACTER_CARD, Genre.XIANXIA);

        assertThat(result).isPresent();
        assertThat(result.get().getGenre()).isEqualTo(Genre.XIANXIA);
        assertThat(result.get().getTemplate()).contains("仙侠专用");
    }

    @Test
    void subStepTemplatesHaveNonEmptyContent() {
        Optional<PromptTemplateEntity> template = repository
                .findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(
                        WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX);

        assertThat(template).isPresent();
        assertThat(template.get().getTemplate()).isNotEmpty();
        assertThat(template.get().getSystemPrompt()).isNotEmpty();
        assertThat(template.get().getTemplate()).contains("{{reportSummary}}");
        assertThat(template.get().getTemplate()).contains("{{originalContent}}");
    }
}
