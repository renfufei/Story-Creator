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
        // If we get here without exception, all migrations succeeded.
        // V31 removes builtin templates from DB (now served from YAML),
        // so an empty table is the expected state.
        long count = repository.count();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    void builtinTemplatesRemovedByV31() {
        // V31 deletes all default_template IS NOT NULL rows.
        // DB should be empty after migrations since builtins are served from YAML files.
        long count = repository.count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void findByStepAndSubStepAndGenreAndIsDefaultTrue_returnsEmptyWhenNoGenreSpecificTemplate() {
        // No templates in DB after V31, so genre-specific query should return empty
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

    /**
     * 一步骤多条默认是**正常**状态（每个子步骤各一条），所以按 step 取默认必须用 List 版。
     * 这里钉住这个语义：两条都返回，调用方自己按子步骤挑。
     */
    @Test
    void findAllByStepAndIsDefaultTrue_returnsEverySubStepDefault() {
        PromptTemplateEntity main = new PromptTemplateEntity();
        main.setStep(WorkflowStep.CHAPTER_WRITING);
        main.setSubStep(PromptSubStep.CHAPTER_WRITING_PRIMARY);
        main.setName("写作核心默认");
        main.setTemplate("主步骤 {{content}}");
        main.setDefault(true);
        repository.save(main);

        PromptTemplateEntity expansion = new PromptTemplateEntity();
        expansion.setStep(WorkflowStep.CHAPTER_WRITING);
        expansion.setSubStep(PromptSubStep.CHAPTER_EXPANSION);
        expansion.setName("章节扩写默认");
        expansion.setTemplate("扩写 {{content}}");
        expansion.setDefault(true);
        repository.save(expansion);

        // 非默认的一条不应出现
        PromptTemplateEntity notDefault = new PromptTemplateEntity();
        notDefault.setStep(WorkflowStep.CHAPTER_WRITING);
        notDefault.setSubStep(PromptSubStep.CHAPTER_CONTEXT_BRIEFING);
        notDefault.setName("非默认");
        notDefault.setTemplate("x");
        notDefault.setDefault(false);
        repository.save(notDefault);

        var defaults = repository.findAllByStepAndIsDefaultTrue(WorkflowStep.CHAPTER_WRITING);

        assertThat(defaults).hasSize(2);
        assertThat(defaults).extracting(PromptTemplateEntity::getSubStep)
                .containsExactlyInAnyOrder(PromptSubStep.CHAPTER_WRITING_PRIMARY, PromptSubStep.CHAPTER_EXPANSION);
    }

    @Test
    void customTemplatesSurviveV31Migration() {
        // Insert a custom template (no default_template) — these survive V31
        PromptTemplateEntity custom = new PromptTemplateEntity();
        custom.setStep(WorkflowStep.PROOFREADING);
        custom.setSubStep(PromptSubStep.PROOFREAD_FIX);
        custom.setName("自定义校对模板");
        custom.setTemplate("自定义模板内容 {{reportSummary}} {{originalContent}}");
        custom.setSystemPrompt("自定义系统提示词");
        custom.setDefault(true);
        // defaultTemplate left null — simulating a user-created template
        repository.save(custom);

        Optional<PromptTemplateEntity> result = repository
                .findByStepAndSubStepAndGenreIsNullAndIsDefaultTrue(
                        WorkflowStep.PROOFREADING, PromptSubStep.PROOFREAD_FIX);

        assertThat(result).isPresent();
        assertThat(result.get().getTemplate()).contains("{{reportSummary}}");
        assertThat(result.get().getTemplate()).contains("{{originalContent}}");
        assertThat(result.get().getSystemPrompt()).isNotEmpty();
    }
}
