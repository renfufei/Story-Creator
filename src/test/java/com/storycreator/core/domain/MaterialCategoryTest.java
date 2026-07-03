package com.storycreator.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialCategoryTest {

    @Test
    void allCategoriesHaveDisplayName() {
        for (MaterialCategory category : MaterialCategory.values()) {
            assertThat(category.getDisplayName()).isNotNull().isNotBlank();
        }
    }

    @Test
    void displayNames_areChinese() {
        assertThat(MaterialCategory.CHARACTER.getDisplayName()).isEqualTo("角色");
        assertThat(MaterialCategory.WORLD.getDisplayName()).isEqualTo("世界观");
        assertThat(MaterialCategory.OUTLINE.getDisplayName()).isEqualTo("大纲");
        assertThat(MaterialCategory.SKILL.getDisplayName()).isEqualTo("金手指/技能");
        assertThat(MaterialCategory.ITEM.getDisplayName()).isEqualTo("道具/武器");
        assertThat(MaterialCategory.OTHER.getDisplayName()).isEqualTo("其他");
    }

    @Test
    void valueOf_works() {
        assertThat(MaterialCategory.valueOf("CHARACTER")).isEqualTo(MaterialCategory.CHARACTER);
        assertThat(MaterialCategory.valueOf("WORLD")).isEqualTo(MaterialCategory.WORLD);
    }

    @Test
    void values_hasSixEntries() {
        assertThat(MaterialCategory.values()).hasSize(6);
    }
}
